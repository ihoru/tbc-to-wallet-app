package su.iho.tbc2wallet.sync

import android.content.Context
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.PayeeLearner
import su.iho.tbc2wallet.data.AccountEntity
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.LabelEntity
import su.iho.tbc2wallet.data.LearnedEntity
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.WalletRuleEntity
import android.util.Log
import java.time.LocalDate

/**
 * Pulls reference data from Wallet: accounts, categories, Wallet's own record rules,
 * and your categorization history (for the learned payee map).
 * Kept infrequent because the API allows ~300 requests/hour.
 */
class Catalog(private val context: Context) {
    private val dao = AppDatabase.get(context).dao()
    private val settings = Settings(context)

    /** Accounts + categories + Wallet rules: 3–5 requests. */
    suspend fun refreshCatalog(api: WalletApi) {
        dao.replaceAccounts(api.accounts().filterNot { it.archived }.map {
            AccountEntity(it.id, it.name, it.currencyCode, it.isBankSync)
        })
        dao.replaceCategories(api.categories().filter { !it.archived && it.enabled }.map {
            CategoryEntity(
                id = it.id, name = it.name, parentName = it.parentName, parentId = it.parentId,
                groupId = it.group?.id, groupName = it.group?.name, color = it.color, systemId = it.systemId,
            )
        })
        dao.replaceWalletRules(api.recordRules().mapNotNull { rule ->
            val catId = rule.category?.id ?: return@mapNotNull null
            WalletRuleEntity(
                id = rule.id,
                keywords = rule.keywords.joinToString("\n"),
                categoryId = catId,
                categoryName = rule.category?.name ?: rule.name,
                fromAccountId = rule.fromAccountId,
            )
        })
        // The labels endpoint isn't in the documented API facts, so a failure here only leaves
        // the rule dialog without labels to pick; it never blocks the rest of the catalog.
        val labels = runCatching { api.labels() }.onFailure { Log.w("Catalog", "labels not loaded", it) }.getOrNull()
        // On failure, drop the cache too: it may belong to a token that's no longer the one in use.
        dao.replaceLabels(labels.orEmpty().filterNot { it.archived }.map { LabelEntity(it.id, it.name) })
        settings.lastCatalogRefresh = System.currentTimeMillis()
    }

    /** Your last 12 months of records: usually 2–10 requests. */
    suspend fun refreshLearned(api: WalletApi) {
        val from = LocalDate.now().minusMonths(12).toString()

        // Find Wallet's "unknown" category ids once (the API expands categoryId=unknown for us).
        if (settings.unknownCategoryIds.isEmpty()) {
            val unknown = api.records(from, categoryId = "unknown", maxPages = 1)
                .mapNotNull { it.category?.id }.toSet()
            if (unknown.isNotEmpty()) settings.unknownCategoryIds = unknown
        }

        // Simulated records must never teach the payee map. The note marker also catches fakes
        // whose Wallet id never reached this device (lost response, reinstall).
        // A transfer's category is Wallet's, not a choice made for the payee, so it isn't learned either.
        val fakeIds = dao.fakeWalletRecordIds().toSet()
        val history = api.records(from)
            .filter { it.transfer == null && it.id !in fakeIds && it.note?.startsWith(SyncWorker.FAKE_NOTE_MARKER) != true }
            .map { PayeeLearner.HistoryRecord(it.id, it.counterParty, it.category?.id, it.category?.name) }
        val assignedByApp = dao.sentAssignments()
            .mapNotNull { a -> a.walletRecordId?.let { it to a.categoryId } }
            .toMap()

        val learned = PayeeLearner.learn(history, settings.unknownCategoryIds, assignedByApp)
        dao.replaceLearned(learned.values.map {
            LearnedEntity(it.payeeKey, it.categoryId, it.categoryName, it.votes, it.totalVotes)
        })
        settings.lastLearnRefresh = System.currentTimeMillis()
    }

    fun catalogStale() = System.currentTimeMillis() - settings.lastCatalogRefresh > DAY_MS
    fun learnedStale() = System.currentTimeMillis() - settings.lastLearnRefresh > 7 * DAY_MS

    private companion object { const val DAY_MS = 24 * 60 * 60 * 1000L }
}
