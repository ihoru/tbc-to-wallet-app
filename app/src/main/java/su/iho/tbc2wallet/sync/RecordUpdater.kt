package su.iho.tbc2wallet.sync

import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.CategorySource
import su.iho.tbc2wallet.data.AppDao
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.parse.TxKind

/** Brings a record already in Wallet in line with your current rules. */
object RecordUpdater {
    sealed class Outcome {
        /** Not in Wallet yet; it picks the rules up when it's sent. */
        data object NotSent : Outcome()
        data object NoRuleMatches : Outcome()
        /** Wallet's API can't switch an existing record between a transfer and a category. */
        data object TransferUnsupported : Outcome()
        data object Unchanged : Outcome()
        data class Updated(val categoryName: String?) : Outcome()
        data class Rejected(val error: String) : Outcome()
    }

    /**
     * Re-runs the category chain for one transaction and, when your rules pick a category, patches its
     * Wallet record with it and the rule's labels. The record may have been recategorized in Wallet since,
     * so the cached category isn't trusted unless [skipIfSame] (the worker, right after sending it).
     * Network and auth errors propagate.
     */
    suspend fun applyRules(dao: AppDao, settings: Settings, api: WalletApi, dedupKey: String, skipIfSame: Boolean = false): Outcome {
        val tx = dao.tx(dedupKey) ?: return Outcome.NotSent
        val recordId = tx.walletRecordId
        if (tx.status != TxStatus.SENT || recordId == null || tx.kind == null) return Outcome.NotSent
        // A conversion is a transfer between your own accounts; rules don't apply to it.
        if (tx.kind == TxKind.CONVERSION.name) return Outcome.NoRuleMatches
        val decision = SyncWorker.buildResolver(dao)
            .resolve(TxKind.valueOf(tx.kind), tx.counterParty, tx.currency?.let { settings.accountFor(it) })
        if (decision.source != CategorySource.USER_RULE) return Outcome.NoRuleMatches
        if (decision.transferAccountId != null || tx.categorySource == SyncWorker.TRANSFER_SOURCE) return Outcome.TransferUnsupported
        if (skipIfSame && decision.categoryId == tx.categoryId && decision.labelIds.isEmpty()) return Outcome.Unchanged
        val patch = WalletApi.RecordPatch(
            id = recordId,
            categoryId = decision.categoryId,
            addLabels = decision.labelIds.ifEmpty { null }?.let { WalletApi.LabelChange(it) },
        )
        val result = api.updateRecords(listOf(patch)).firstOrNull()
        if (result?.success != true) return Outcome.Rejected(result?.error ?: "no answer")
        // Re-read: the row may have been deleted from the list while the request was out.
        dao.tx(dedupKey)?.let {
            dao.upsertTx(it.copy(categoryId = decision.categoryId, categoryName = decision.categoryName,
                categorySource = CategorySource.USER_RULE.name))
        }
        return Outcome.Updated(decision.categoryName)
    }
}
