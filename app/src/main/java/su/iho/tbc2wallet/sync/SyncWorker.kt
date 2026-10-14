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
import su.iho.tbc2wallet.categorize.CategoryResolver
import su.iho.tbc2wallet.categorize.LearnedEntry
import su.iho.tbc2wallet.categorize.WalletRule
import su.iho.tbc2wallet.data.AppDao
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.toUserRule
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

            // Fakes held back by a label-endpoint outage are retried with backoff.
            if (sendQueued(api, token)) Result.retry() else Result.success()
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

    /** Returns true when fake records were held back because the label lookup hit a transient error. */
    private suspend fun sendQueued(api: WalletApi, token: String): Boolean {
        val queue = dao.toSend()
        if (queue.isEmpty()) { note("Up to date"); return false }

        val rulesAtStart = dao.userRules()
        val resolver = buildResolver(dao)
        // Only look the label up when a fake record can actually be sent this run.
        val sendableFake = queue.any { tx -> tx.isFake && tx.currency?.let { settings.accountFor(it) } != null }
        // A transient label failure (5xx, network) only holds back the fakes; real records still go.
        // Rate limits and auth errors still stop the whole run.
        var fakesDeferred = false
        val fakeLabel = if (!sendableFake) null else try {
            fakeLabelId(api, token)
        } catch (e: java.io.IOException) {
            if (e is WalletApi.ApiException && e !is WalletApi.ApiException.Http) throw e
            Log.w(TAG, "label lookup failed; fake records wait for the next run", e)
            fakesDeferred = true
            null
        }
        val missingAccounts = mutableSetOf<String>()
        val ready = mutableListOf<Pair<TransactionEntity, WalletApi.NewRecord>>()
        var sent = 0
        val sentKeys = mutableListOf<String>()
        var failed = 0
        var lastError = ""

        for (tx in queue) {
            val currency = tx.currency ?: continue
            val accountId = settings.accountFor(currency)
            if (accountId == null) {
                missingAccounts += currency
                if (tx.status != TxStatus.NEEDS_ACCOUNT) dao.upsertTx(tx.copy(status = TxStatus.NEEDS_ACCOUNT))
                continue
            }
            if (tx.isFake && fakesDeferred) continue
            val kind = TxKind.valueOf(tx.kind!!)
            val decision = resolver.resolve(kind, tx.counterParty, accountId)
            if (decision.transferAccountId == accountId) {
                // Wallet can't put both sides of a transfer in one account, so it would reject this.
                val error = "Your rule makes this a transfer with the same account it's recorded in " +
                    "(${decision.transferAccountName ?: accountId}). Pick another account in the rule, then retry."
                dao.upsertTx(tx.copy(status = TxStatus.FAILED, error = error))
                failed++; lastError = error
                continue
            }
            val value = tx.amount!!.toBigDecimal().let { if (kind == TxKind.EXPENSE) it.negate() else it }
            val record = WalletApi.NewRecord(
                accountId = accountId,
                amount = WalletApi.Amount(value.toDouble(), currency),
                recordDate = tx.recordDateUtc!!,
                categoryId = decision.categoryId,
                counterParty = tx.counterParty,
                note = (tx.cardLast4?.let { "TBC ***$it" } ?: tx.accountLabel)
                    .let { if (tx.isFake) listOfNotNull(FAKE_NOTE_MARKER, it).joinToString(" ") else it },
                labelIds = (decision.labelIds + listOfNotNull(fakeLabel.takeIf { tx.isFake })).distinct().ifEmpty { null },
                transfer = decision.transferAccountId?.let { WalletApi.Transfer(it, pairingMode = "new") },
            )
            ready += if (decision.transferAccountId != null) tx.copy(
                categoryId = null,
                categoryName = decision.transferAccountName ?: decision.transferAccountId,
                categorySource = TRANSFER_SOURCE,
            ) to record else tx.copy(
                categoryId = decision.categoryId,
                categoryName = decision.categoryName,
                categorySource = decision.source.name,
            ) to record
        }

        val labelRejected = mutableListOf<Pair<TransactionEntity, WalletApi.NewRecord>>()
        suspend fun send(batch: List<Pair<TransactionEntity, WalletApi.NewRecord>>, retryWithoutLabel: Boolean) {
            for (all in batch.chunked(20)) {
                // Skip anything you deleted since the queue was read.
                val chunk = all.filter { dao.statusOf(it.first.dedupKey) != TxStatus.DELETED }
                if (chunk.isEmpty()) continue
                val results = api.createRecords(chunk.map { it.second }).associateBy { it.inputIndex }
                chunk.forEachIndexed { i, (tx, record) ->
                    val r = results[i]
                    when {
                        r?.success == true -> {
                            sent++; sentKeys += tx.dedupKey
                            dao.upsertTx(tx.copy(status = TxStatus.SENT, walletRecordId = r.id, error = null))
                        }
                        // The fake label may have been deleted in Wallet since we looked it up: retry without it.
                        r?.errorType == "client_error" && retryWithoutLabel && tx.isFake && fakeLabel != null &&
                            record.labelIds.orEmpty().contains(fakeLabel) ->
                            labelRejected += tx to record.copy(labelIds = (record.labelIds.orEmpty() - fakeLabel).ifEmpty { null })
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
        send(ready, retryWithoutLabel = true)
        if (labelRejected.isNotEmpty()) {
            settings.recentFakeLabel = null
            send(labelRejected, retryWithoutLabel = false)
        }

        // A rule saved while these were on their way: bring the new records in line with it.
        if (sentKeys.isNotEmpty() && dao.userRules() != rulesAtStart) for (key in sentKeys) {
            runCatching { RecordUpdater.applyRules(dao, settings, api, key, skipIfSame = true) }
                .onFailure { Log.w(TAG, "re-applying rules to $key failed", it) }
        }

        if (missingAccounts.isNotEmpty()) Notifier.needsAccount(applicationContext, missingAccounts)
        if (failed > 0) Notifier.failed(applicationContext, failed, lastError)
        note("Sent $sent, failed $failed" +
            (if (missingAccounts.isNotEmpty()) ", waiting for account: ${missingAccounts.joinToString()}" else "") +
            (if (fakesDeferred) ". Fake records wait: Wallet's label service didn't answer"
            else if (labelRejected.isNotEmpty() || (ready.any { it.first.isFake } && fakeLabel == null))
                ". Couldn't find or create the \"$FAKE_LABEL\" label in Wallet, so fake records are marked in the note only" else ""))
        return fakesDeferred
    }

    /**
     * Finds the active Wallet label named "fake", creating it if missing. It is looked up on every
     * run that has fake records (they are rare), so a label renamed, archived or deleted in Wallet
     * is never reused. Returns null when the label API refuses the request (a 4xx); the record
     * still goes out with "[fake]" in its note, as it does when Wallet rejects a record for the
     * label (deleted since the lookup, or cached from another account's token). Rate limits, 5xx, auth and network errors propagate
     * so the worker backs off as for any other call.
     */
    private suspend fun fakeLabelId(api: WalletApi, token: String): String? {
        val labels = try {
            api.labels()
        } catch (e: WalletApi.ApiException.Http) {
            if (e.code >= 500) throw e
            Log.w(TAG, "listing labels failed", e); return null
        }
        labels.firstOrNull { !it.archived && it.name.equals(FAKE_LABEL, true) }?.let { return it.id }
        // A label created in the last few minutes may not be listed yet; reuse it, don't duplicate it.
        // Only when Wallet doesn't list that id at all; if it lists it renamed or archived, it's stale.
        settings.recentFakeLabel?.let { (id, at) ->
            if (System.currentTimeMillis() - at < RECENT_LABEL_MS && labels.none { it.id == id }) return id
        }
        return try {
            api.createLabel(FAKE_LABEL)?.also {
                // Skip the cache if the token changed while this run was creating the label.
                if (settings.apiToken == token) settings.recentFakeLabel = it to System.currentTimeMillis()
            }
        } catch (e: WalletApi.ApiException.Http) {
            if (e.code >= 500) throw e
            Log.w(TAG, "creating label failed", e); null
        }
    }

    private fun note(message: String) {
        settings.lastSyncMessage = "${java.time.LocalDateTime.now().withNano(0)}  $message"
        Log.i(TAG, message)
    }

    companion object {
        private const val TAG = "SyncWorker"
        const val FAKE_LABEL = "fake"
        const val FAKE_NOTE_MARKER = "[fake]"
        /** categorySource of a transaction sent as a transfer; categoryName then holds the other account. */
        const val TRANSFER_SOURCE = "TRANSFER"
        private const val RECENT_LABEL_MS = 15 * 60 * 1000L
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The same priority chain the worker sends with: your rules, Wallet's rules, then the learned map. */
        suspend fun buildResolver(dao: AppDao): CategoryResolver {
            val userRules = dao.userRules().map { it.toUserRule() }
            val walletRules = dao.walletRules().map {
                WalletRule(it.keywords.split("\n"), it.categoryId, it.categoryName, it.fromAccountId)
            }
            val learned = dao.learned().associate {
                it.payeeKey to LearnedEntry(it.payeeKey, it.categoryId, it.categoryName, it.votes, it.totalVotes)
            }
            return CategoryResolver(userRules, walletRules, learned)
        }

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
