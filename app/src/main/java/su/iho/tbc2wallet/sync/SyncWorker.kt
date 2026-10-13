package su.iho.tbc2wallet.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.AppliesTo
import su.iho.tbc2wallet.categorize.CategoryResolver
import su.iho.tbc2wallet.categorize.LearnedEntry
import su.iho.tbc2wallet.categorize.MatchType
import su.iho.tbc2wallet.categorize.UserRule
import su.iho.tbc2wallet.categorize.WalletRule
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.ingest.Notifier
import su.iho.tbc2wallet.parse.TxKind
import java.util.concurrent.TimeUnit

/**
 * Sends queued transactions to Wallet. Runs when a new transaction arrives (only with
 * network) and every 6 hours as a safety net; retries with backoff on network errors.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dao = AppDatabase.get(context).dao()
    private val settings = Settings(context)

    override suspend fun doWork(): Result {
        val token = settings.apiToken ?: return Result.success().also { note("No API token set") }
        val api = WalletApi(token)
        val catalog = Catalog(applicationContext)

        return try {
            if (catalog.catalogStale()) catalog.refreshCatalog(api)
            if (catalog.learnedStale()) runCatching { catalog.refreshLearned(api) }
                .onFailure { Log.w(TAG, "learning skipped", it) }

            sendQueued(api)
            Result.success()
        } catch (e: WalletApi.ApiException.Unauthorized) {
            note("Wallet rejected the API token. Paste a new one in Settings.")
            Notifier.failed(applicationContext, dao.toSend().size, "Wallet rejected the API token.")
            Result.success()
        } catch (e: WalletApi.ApiException) {
            note(e.message ?: "Wallet error"); Result.retry()
        } catch (e: java.io.IOException) {
            note("Network error: ${e.message}"); Result.retry()
        }
    }

    private suspend fun sendQueued(api: WalletApi) {
        val queue = dao.toSend()
        if (queue.isEmpty()) { note("Up to date"); return }

        val resolver = buildResolver()
        val fakeLabel = if (queue.any { it.isFake }) fakeLabelId(api) else null
        val missingAccounts = mutableSetOf<String>()
        val ready = mutableListOf<Pair<TransactionEntity, WalletApi.NewRecord>>()

        for (tx in queue) {
            val currency = tx.currency ?: continue
            val accountId = settings.accountFor(currency)
            if (accountId == null) {
                missingAccounts += currency
                if (tx.status != TxStatus.NEEDS_ACCOUNT) dao.upsertTx(tx.copy(status = TxStatus.NEEDS_ACCOUNT))
                continue
            }
            val kind = TxKind.valueOf(tx.kind!!)
            val decision = resolver.resolve(kind, tx.counterParty, accountId)
            val value = tx.amount!!.toBigDecimal().let { if (kind == TxKind.EXPENSE) it.negate() else it }
            val record = WalletApi.NewRecord(
                accountId = accountId,
                amount = WalletApi.Amount(value.toDouble(), currency),
                recordDate = tx.recordDateUtc!!,
                categoryId = decision.categoryId,
                counterParty = tx.counterParty,
                note = (tx.cardLast4?.let { "TBC ***$it" } ?: tx.accountLabel)
                    .let { if (tx.isFake) listOfNotNull("[fake]", it).joinToString(" ") else it },
                labelIds = if (tx.isFake) fakeLabel?.let { listOf(it) } else null,
            )
            ready += tx.copy(
                categoryId = decision.categoryId,
                categoryName = decision.categoryName,
                categorySource = decision.source.name,
            ) to record
        }

        var failed = 0
        var lastError = ""
        // Fake records rejected while carrying the label are sent once more without it, in case
        // the cached label was deleted in Wallet; the cache is cleared so the next run looks it up.
        val retryWithoutLabel = mutableListOf<Pair<TransactionEntity, WalletApi.NewRecord>>()
        suspend fun send(batch: List<Pair<TransactionEntity, WalletApi.NewRecord>>, allowLabelRetry: Boolean) {
            for (chunk in batch.chunked(20)) {
                val results = api.createRecords(chunk.map { it.second }).associateBy { it.inputIndex }
                chunk.forEachIndexed { i, (tx, record) ->
                    val r = results[i]
                    when {
                        r?.success == true -> dao.upsertTx(tx.copy(status = TxStatus.SENT, walletRecordId = r.id, error = null))
                        r?.errorType == "client_error" && allowLabelRetry && record.labelIds != null -> {
                            settings.fakeLabelId = null
                            retryWithoutLabel += tx to record.copy(labelIds = null)
                        }
                        r?.errorType == "client_error" -> {
                            failed++; lastError = r.error.orEmpty()
                            dao.upsertTx(tx.copy(status = TxStatus.FAILED, error = r.error))
                        }
                        // server_error or missing result: keep it queued for the next run
                        else -> dao.upsertTx(tx.copy(status = TxStatus.PENDING, error = r?.error ?: "No result from Wallet"))
                    }
                }
            }
        }
        send(ready, allowLabelRetry = true)
        if (retryWithoutLabel.isNotEmpty()) send(retryWithoutLabel, allowLabelRetry = false)

        if (missingAccounts.isNotEmpty()) Notifier.needsAccount(applicationContext, missingAccounts)
        if (failed > 0) Notifier.failed(applicationContext, failed, lastError)
        note("Sent ${ready.size - failed}, failed $failed" +
            (if (missingAccounts.isNotEmpty()) ", waiting for account: ${missingAccounts.joinToString()}" else "") +
            (if (ready.any { it.first.isFake } && (fakeLabel == null || retryWithoutLabel.isNotEmpty()))
                ". Couldn't find or create the \"$FAKE_LABEL\" label in Wallet, so fake records are marked in the note only" else ""))
    }

    /**
     * Finds the Wallet label named "fake", creating it if needed. Returns null when it can't
     * (the record still goes out, with "[fake]" in its note), so a test never blocks the queue.
     */
    private suspend fun fakeLabelId(api: WalletApi): String? {
        settings.fakeLabelId?.let { return it }
        // Only an HTTP error (endpoint missing or request refused) falls back to note-only. Rate
        // limits, auth and network errors propagate so the worker backs off as for any other call.
        suspend fun <T> orNullOnHttpError(what: String, block: suspend () -> T): T? =
            try { block() } catch (e: WalletApi.ApiException.Http) { Log.w(TAG, "$what failed", e); null }
        val id = orNullOnHttpError("listing labels") {
            api.labels().firstOrNull { !it.archived && it.name.equals(FAKE_LABEL, true) }?.id
        } ?: orNullOnHttpError("creating label") { api.createLabel(FAKE_LABEL) }
        settings.fakeLabelId = id
        return id
    }

    private suspend fun buildResolver(): CategoryResolver {
        val userRules = dao.userRules().map {
            UserRule(it.pattern, MatchType.valueOf(it.matchType), AppliesTo.valueOf(it.appliesTo), it.categoryId, it.categoryName)
        }
        val walletRules = dao.walletRules().map {
            WalletRule(it.keywords.split("\n"), it.categoryId, it.categoryName, it.fromAccountId)
        }
        val learned = dao.learned().associate {
            it.payeeKey to LearnedEntry(it.payeeKey, it.categoryId, it.categoryName, it.votes, it.totalVotes)
        }
        return CategoryResolver(userRules, walletRules, learned)
    }

    private fun note(message: String) {
        settings.lastSyncMessage = "${java.time.LocalDateTime.now().withNano(0)}  $message"
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val FAKE_LABEL = "fake"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            // APPEND_OR_REPLACE: a burst of notifications becomes one queued run, never lost.
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
