package su.iho.tbc2wallet

import android.app.Application
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.CategorySource
import su.iho.tbc2wallet.data.LearnedEntity
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.ingest.Ingest
import su.iho.tbc2wallet.ingest.TbcNotificationListener
import su.iho.tbc2wallet.parse.TxKind
import su.iho.tbc2wallet.sync.Catalog
import su.iho.tbc2wallet.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    val settings = Settings(app)

    val transactions = dao.observeTransactions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val userRules = dao.observeUserRules().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val categories = dao.observeCategories().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val labels = dao.observeLabels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val accounts = dao.observeAccounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val walletRuleCount = dao.observeWalletRuleCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val learned = dao.observeLearned().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Bumped to make the settings screen re-read plain preferences. */
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion.asStateFlow()

    fun hasNotificationAccess(): Boolean {
        val ctx = getApplication<Application>()
        val flat = Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, TbcNotificationListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    fun clearMessage() { _message.value = null }

    fun saveToken(token: String) {
        settings.apiToken = token
        settings.recentFakeLabel = null   // it belonged to the previous token's Wallet
        _settingsVersion.value++
        refreshFromWallet(learn = true)
    }

    /** Test the token and pull accounts, categories, Wallet rules and (optionally) history. */
    fun refreshFromWallet(learn: Boolean) = viewModelScope.launch {
        val token = settings.apiToken ?: run { _message.value = "Add your Wallet API token first."; return@launch }
        _busy.value = true
        try {
            val api = WalletApi(token)
            val catalog = Catalog(getApplication())
            catalog.refreshCatalog(api)
            if (learn) catalog.refreshLearned(api)
            _message.value = "Updated from Wallet."
            SyncWorker.enqueue(getApplication())
        } catch (e: WalletApi.ApiException.Unauthorized) {
            _message.value = "Wallet rejected this token. Check it and try again."
        } catch (e: WalletApi.ApiException.InitialSync) {
            _message.value = "Wallet is still preparing API access for a new token. Try again in a few minutes."
        } catch (e: Exception) {
            _message.value = "Couldn't reach Wallet: ${e.message}"
        } finally {
            _busy.value = false
            _settingsVersion.value++
        }
    }

    fun setAccount(currency: String, accountId: String?) {
        settings.setAccountFor(currency, accountId)
        _settingsVersion.value++
        viewModelScope.launch { dao.requeueNeedsAccount(); SyncWorker.enqueue(getApplication()) }
    }

    fun setTbcPackage(pkg: String) { settings.tbcPackage = pkg; _settingsVersion.value++ }

    fun syncNow() = SyncWorker.enqueue(getApplication())

    fun retry(tx: TransactionEntity) = viewModelScope.launch {
        dao.setStatus(tx.dedupKey, TxStatus.PENDING); SyncWorker.enqueue(getApplication())
    }

    /** Runs typed-in text through the same path as a real TBC notification, marked as fake. */
    fun simulateNotification(text: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        Ingest.handle(getApplication(), text.trim(), notificationKey = "fake", whenMillis = now, postTime = now, fake = true)
    }

    fun ignore(tx: TransactionEntity) = viewModelScope.launch { dao.setStatus(tx.dedupKey, TxStatus.IGNORED) }

    /** Hides [tx] from the app's own list and stops it being sent; a record already in Wallet stays there. */
    fun deleteTransaction(tx: TransactionEntity) = viewModelScope.launch { dao.setStatus(tx.dedupKey, TxStatus.DELETED) }

    /**
     * Inserts [rule] when its id is 0, otherwise replaces the existing rule with that id.
     * [fromTx] is the Activity card the rule was made from: if it's already in Wallet, its record
     * there gets the rule's category and labels too. One still queued picks the rule up when it's sent.
     */
    fun saveRule(rule: UserRuleEntity, fromTx: TransactionEntity? = null) = viewModelScope.launch {
        val clean = rule.copy(pattern = rule.pattern.trim())
        if (clean.id == 0L) dao.insertUserRule(clean) else dao.updateUserRule(clean)
        fromTx?.let { applyRulesToSent(it.dedupKey) }
    }

    /** Re-runs the category chain for a transaction already in Wallet and patches its record to match. */
    private suspend fun applyRulesToSent(dedupKey: String) {
        val tx = dao.tx(dedupKey) ?: return
        val recordId = tx.walletRecordId
        if (tx.status != TxStatus.SENT || recordId == null || tx.kind == null) return
        val decision = SyncWorker.buildResolver(dao)
            .resolve(TxKind.valueOf(tx.kind), tx.counterParty, tx.currency?.let { settings.accountFor(it) })
        if (decision.source != CategorySource.USER_RULE) {
            _message.value = "Rule saved. It doesn't match this transaction, so its record in Wallet stays as it is."
            return
        }
        if (decision.transferAccountId != null || tx.categorySource == SyncWorker.TRANSFER_SOURCE) {
            _message.value = "Rule saved. Wallet can't switch an existing record between a transfer and a category, " +
                "so change this one in Wallet."
            return
        }
        if (decision.categoryId == tx.categoryId && decision.labelIds.isEmpty()) {
            if (tx.categorySource != CategorySource.USER_RULE.name) dao.upsertTx(tx.copy(categorySource = CategorySource.USER_RULE.name))
            return
        }
        val token = settings.apiToken ?: run { _message.value = "Rule saved. Add your Wallet API token to update the record in Wallet."; return }
        val patch = WalletApi.RecordPatch(
            id = recordId,
            categoryId = decision.categoryId,
            addLabels = decision.labelIds.ifEmpty { null }?.let { WalletApi.LabelChange(it) },
        )
        _message.value = try {
            val result = WalletApi(token).updateRecords(listOf(patch)).firstOrNull()
            if (result?.success == true) {
                // Re-read: the row may have been deleted from the list while the request was out.
                dao.tx(dedupKey)?.let {
                    dao.upsertTx(it.copy(categoryId = decision.categoryId, categoryName = decision.categoryName,
                        categorySource = CategorySource.USER_RULE.name))
                }
                "Rule saved, and the record in Wallet now has ${decision.categoryName}."
            } else {
                "Rule saved, but Wallet didn't update the record: ${result?.error ?: "no answer"}"
            }
        } catch (e: WalletApi.ApiException.Unauthorized) {
            "Rule saved, but Wallet rejected the API token, so the record there wasn't updated."
        } catch (e: java.io.IOException) {
            "Rule saved, but the record in Wallet wasn't updated: ${e.message}"
        }
    }

    fun deleteRule(rule: UserRuleEntity) = viewModelScope.launch { dao.deleteUserRule(rule.id) }

    /** Drops a learned payee and keeps it out of later re-learns. */
    fun forgetLearned(entry: LearnedEntity) = viewModelScope.launch {
        settings.forgottenPayees = settings.forgottenPayees + entry.payeeKey
        dao.deleteLearned(entry.payeeKey)
    }
}
