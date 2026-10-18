package su.iho.tbc2wallet.sync

import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.AppliesTo
import su.iho.tbc2wallet.categorize.CategoryResolver
import su.iho.tbc2wallet.categorize.MatchType
import su.iho.tbc2wallet.categorize.UserRule
import su.iho.tbc2wallet.categorize.matches
import su.iho.tbc2wallet.parse.TxKind
import java.time.LocalDate

/**
 * Finds records already in Wallet that a rule would categorize differently, and moves them to the
 * rule's category. Wallet's API can't turn a record into a transfer, so only category rules apply.
 */
object RuleBackfill {
    /** How far back to look, the same window the learned payees come from. */
    const val MONTHS = 12L

    /** A record the rule would change, as listed for you to tick. */
    data class Item(
        val recordId: String,
        val payee: String,
        val recordDate: String?,
        val amount: Double?,
        val currency: String?,
        val categoryId: String?,
        val categoryName: String?,
        /** Why Wallet didn't take the last attempt, if it didn't. */
        val error: String? = null,
    )

    data class Applied(
        val updatedIds: List<String>,
        /** recordId -> Wallet's reason. */
        val errors: Map<String, String>,
        /** Set when Wallet's hourly limit stopped the run; the rest weren't tried. */
        val retryAfterSeconds: Long? = null,
        /** Set when another error stopped the run after some records were updated; the rest weren't tried. */
        val stoppedBy: Exception? = null,
    )

    /**
     * Text to narrow the Wallet search by payee. An exact rule compares payees with digits and
     * punctuation dropped, so search for its longest word; the phone does the real match.
     */
    fun searchText(rule: UserRule): String? = when (rule.matchType) {
        MatchType.CONTAINS -> rule.pattern.trim()
        MatchType.EXACT -> CategoryResolver.payeeKey(rule.pattern).split(" ").maxByOrNull { it.length }
    }?.takeIf { it.isNotEmpty() }

    fun recordType(rule: UserRule): String? = when (rule.appliesTo) {
        AppliesTo.ANY -> null
        AppliesTo.EXPENSE -> "expense"
        AppliesTo.INCOME -> "income"
    }

    /**
     * The [records] that `rules[ruleIndex]` would win, the way new transactions are categorized
     * (exact before contains, longer patterns first), and that don't have the rule's category and all
     * its labels already.
     */
    fun pick(rules: List<UserRule>, ruleIndex: Int, records: List<WalletApi.Record>): List<Item> {
        val rule = rules[ruleIndex]
        val order = rules.indices.sortedWith(compareBy(CategoryResolver.RULE_PRIORITY) { rules[it] })
        return records.mapNotNull { r ->
            val payee = r.counterParty?.trim().orEmpty()
            val value = r.amount?.value ?: return@mapNotNull null
            if (payee.isEmpty() || value == 0.0 || r.transfer != null) return@mapNotNull null
            if (r.category?.id == rule.categoryId && rule.labelIds.all { id -> r.labels.any { it.id == id } }) return@mapNotNull null
            val kind = if (value < 0) TxKind.EXPENSE else TxKind.INCOME
            if (order.firstOrNull { rules[it].matches(kind, payee) } != ruleIndex) return@mapNotNull null
            Item(r.id, payee, r.recordDate, value, r.amount?.currencyCode, r.category?.id, r.category?.name)
        }
    }

    /**
     * Looks up the records [rules][ruleIndex] would change in [accountIds] over the last [MONTHS], plus
     * [alsoRecordId] (the Activity card the rule was made from) wherever and whenever it is.
     */
    suspend fun find(
        api: WalletApi, rules: List<UserRule>, ruleIndex: Int, accountIds: List<String>,
        alsoRecordId: String? = null, today: LocalDate = LocalDate.now(),
    ): List<Item> {
        val rule = rules[ruleIndex]
        if (rule.transferAccountId != null) return emptyList()
        val from = today.minusMonths(MONTHS).toString()
        val records = accountIds.distinct().chunked(10).flatMap { api.searchRecords(from, it, searchText(rule), recordType(rule)) }
        val extra = alsoRecordId?.takeIf { id -> records.none { it.id == id } }?.let { api.recordsById(listOf(it)) }.orEmpty()
        return pick(rules, ruleIndex, (extra + records).distinctBy { it.id })
    }

    /**
     * Gives [recordIds] the rule's category and adds its labels, 10 per request. Records succeed or
     * fail one by one. A rate limit stops the run, and so does any other error once some records are
     * updated, so those aren't lost; an error before that propagates.
     */
    suspend fun apply(api: WalletApi, rule: UserRule, recordIds: List<String>): Applied {
        val updated = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()
        val labels = rule.labelIds.ifEmpty { null }?.let { WalletApi.LabelChange(it) }
        for (chunk in recordIds.chunked(10)) {
            val results = try {
                api.updateRecords(chunk.map { WalletApi.RecordPatch(it, categoryId = rule.categoryId, addLabels = labels) })
            } catch (e: WalletApi.ApiException.RateLimited) {
                return Applied(updated, errors, retryAfterSeconds = e.retryAfterSeconds)
            } catch (e: Exception) {
                if (updated.isEmpty()) throw e
                return Applied(updated, errors, stoppedBy = e)
            }
            chunk.forEachIndexed { i, id ->
                val result = results.firstOrNull { it.inputIndex == i }
                if (result?.success == true) updated += id else errors[id] = result?.error ?: "no answer from Wallet"
            }
        }
        return Applied(updated, errors)
    }
}
