package su.iho.tbc2wallet

import android.app.Application
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.ingest.TbcNotificationListener
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

    fun ignore(tx: TransactionEntity) = viewModelScope.launch { dao.setStatus(tx.dedupKey, TxStatus.IGNORED) }

    /** Inserts [rule] when its id is 0, otherwise replaces the existing rule with that id. */
    fun saveRule(rule: UserRuleEntity) = viewModelScope.launch {
        val clean = rule.copy(pattern = rule.pattern.trim())
        if (clean.id == 0L) dao.insertUserRule(clean) else dao.updateUserRule(clean)
    }

    fun deleteRule(rule: UserRuleEntity) = viewModelScope.launch { dao.deleteUserRule(rule.id) }
}
