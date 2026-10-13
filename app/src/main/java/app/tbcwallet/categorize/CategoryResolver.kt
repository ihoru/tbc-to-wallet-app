package app.tbcwallet.categorize

import app.tbcwallet.parse.TxKind
import java.text.Normalizer

enum class CategorySource { USER_RULE, WALLET_RULE, LEARNED, NONE }

data class CategoryDecision(
    val categoryId: String?,
    val categoryName: String?,
    val source: CategorySource,
)

enum class MatchType { CONTAINS, EXACT }
enum class AppliesTo { ANY, EXPENSE, INCOME }

/** A rule the user created in this app. Highest priority. */
data class UserRule(
    val pattern: String,
    val matchType: MatchType,
    val appliesTo: AppliesTo,
    val categoryId: String,
    val categoryName: String,
)

/** A categorization rule defined in Wallet itself (GET /v1/api/record-rules). */
data class WalletRule(
    val keywords: List<String>,
    val categoryId: String,
    val categoryName: String,
    /** When set, Wallet only applies the rule to records in this account. */
    val fromAccountId: String?,
)

/** How often a payee was filed under a category in your Wallet history. */
data class LearnedEntry(
    val payeeKey: String,
    val categoryId: String,
    val categoryName: String,
    val votes: Int,
    val totalVotes: Int,
)

/**
 * Priority chain:
 *  1. your own rules in this app (always win)
 *  2. Wallet's record rules (the categorization Wallet itself would apply)
 *  3. the map learned from how you categorized this payee in the past
 *  4. nothing -> Wallet files it as "Unknown"
 */
class CategoryResolver(
    userRules: List<UserRule>,
    private val walletRules: List<WalletRule>,
    private val learned: Map<String, LearnedEntry>,
    private val minVotes: Int = 2,
    private val minShare: Double = 0.6,
) {
    // Longer / exact patterns are more specific, so they are checked first.
    private val userRules = userRules.sortedWith(
        compareByDescending<UserRule> { it.matchType == MatchType.EXACT }
            .thenByDescending { it.pattern.length }
    )

    fun resolve(kind: TxKind, counterParty: String?, accountId: String?): CategoryDecision {
        val payee = counterParty?.trim().orEmpty()
        if (payee.isEmpty()) return NONE

        userRules.firstOrNull { it.appliesTo(kind) && it.matches(payee) }?.let {
            return CategoryDecision(it.categoryId, it.categoryName, CategorySource.USER_RULE)
        }

        walletRules.firstOrNull { rule ->
            (rule.fromAccountId == null || rule.fromAccountId == accountId) &&
                rule.keywords.any { kw -> kw.isNotBlank() && payee.contains(kw.trim(), ignoreCase = true) }
        }?.let {
            return CategoryDecision(it.categoryId, it.categoryName, CategorySource.WALLET_RULE)
        }

        learned[payeeKey(payee)]?.let {
            val share = it.votes.toDouble() / it.totalVotes
            if (it.votes >= minVotes && share >= minShare) {
                return CategoryDecision(it.categoryId, it.categoryName, CategorySource.LEARNED)
            }
        }
        return NONE
    }

    private fun UserRule.appliesTo(kind: TxKind) = when (appliesTo) {
        AppliesTo.ANY -> true
        AppliesTo.EXPENSE -> kind == TxKind.EXPENSE
        AppliesTo.INCOME -> kind == TxKind.INCOME
    }

    private fun UserRule.matches(payee: String) = when (matchType) {
        MatchType.EXACT -> payeeKey(payee) == payeeKey(pattern)
        MatchType.CONTAINS -> payee.contains(pattern.trim(), ignoreCase = true)
    }

    companion object {
        private val NONE = CategoryDecision(null, null, CategorySource.NONE)

        /**
         * Normalizes a payee so small variations map to one key:
         * "WOLT*1234 Tbilisi" and "Wolt 5678 TBILISI" -> "WOLT TBILISI".
         */
        fun payeeKey(raw: String): String =
            Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .uppercase()
                .replace(Regex("""[^\p{L}\s]"""), " ") // drop digits and punctuation
                .replace(Regex("""\s+"""), " ")
                .trim()
    }
}

/**
 * Builds the learned map from past Wallet records.
 * A record created by this app that still has the category the app assigned is skipped,
 * so the app doesn't reinforce its own guesses. If you changed it in Wallet, that
 * correction counts triple.
 */
object PayeeLearner {
    data class HistoryRecord(
        val recordId: String,
        val counterParty: String?,
        val categoryId: String?,
        val categoryName: String?,
    )

    fun learn(
        history: List<HistoryRecord>,
        unknownCategoryIds: Set<String>,
        /** recordId -> categoryId this app assigned when creating it. */
        assignedByApp: Map<String, String?>,
    ): Map<String, LearnedEntry> {
        data class Vote(val key: String, val categoryId: String, val categoryName: String, val weight: Int)

        val votes = history.mapNotNull { r ->
            val payee = r.counterParty?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val catId = r.categoryId ?: return@mapNotNull null
            if (catId in unknownCategoryIds) return@mapNotNull null
            val weight = when {
                r.recordId !in assignedByApp -> 1                  // created elsewhere
                assignedByApp[r.recordId] == catId -> return@mapNotNull null // our own guess, untouched
                else -> 3                                           // you corrected our guess
            }
            val key = CategoryResolver.payeeKey(payee)
            if (key.isEmpty()) null else Vote(key, catId, r.categoryName ?: catId, weight)
        }

        return votes.groupBy { it.key }.mapValues { (key, list) ->
            val total = list.sumOf { it.weight }
            val (catId, catVotes) = list.groupBy { it.categoryId }
                .mapValues { (_, v) -> v.sumOf { it.weight } }
                .maxBy { it.value }
            LearnedEntry(
                payeeKey = key,
                categoryId = catId,
                categoryName = list.first { it.categoryId == catId }.categoryName,
                votes = catVotes,
                totalVotes = total,
            )
        }
    }
}
