package su.iho.tbc2wallet

import android.app.Application
import android.content.ComponentName
import android.provider.Settings.Secure
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.data.LearnedEntity
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.Settings
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.data.toUserRule
import su.iho.tbc2wallet.data.labelIdList
import su.iho.tbc2wallet.categorize.RuleClashes
import su.iho.tbc2wallet.categorize.UserRule
import su.iho.tbc2wallet.ingest.Ingest
import su.iho.tbc2wallet.ingest.TbcNotificationListener
import su.iho.tbc2wallet.parse.TxKind
import su.iho.tbc2wallet.sync.Catalog
import su.iho.tbc2wallet.sync.RuleBackfill
import su.iho.tbc2wallet.sync.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** Charges cancelled by reversals, by key; they're off the list, but their reversal names them. */
    val reversedCharges = dao.observeReversedCharges().map { list -> list.associateBy { it.dedupKey } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Bumped to make the settings screen re-read plain preferences. */
    private val _settingsVersion = MutableStateFlow(0)
    val settingsVersion: StateFlow<Int> = _settingsVersion.asStateFlow()

    init {
        // Copies saved before duplicates were blocked (or restored from such a backup) change nothing; drop them.
        viewModelScope.launch {
            RuleClashes.duplicates(dao.userRules()) { it.toUserRule() }.forEach { dao.deleteUserRule(it.id) }
        }
    }

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
        dao.setStatus(tx.dedupKey, if (tx.kind == TxKind.REVERSAL.name) TxStatus.REVERSING else TxStatus.PENDING)
        SyncWorker.enqueue(getApplication())
    }

    /** Runs typed-in text through the same path as a real TBC notification, marked as fake. */
    fun simulateNotification(text: String) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        Ingest.handle(getApplication(), text.trim(), notificationKey = "fake", whenMillis = now, postTime = now, fake = true)
    }

    fun ignore(tx: TransactionEntity) = viewModelScope.launch { dao.setStatus(tx.dedupKey, TxStatus.IGNORED) }

    /** Hides [tx] from the app's own list and stops it being sent; a record already in Wallet stays there. */
    fun deleteTransaction(tx: TransactionEntity) = viewModelScope.launch { dao.setStatus(tx.dedupKey, TxStatus.DELETED) }

    /** Older Wallet records a rule would change, listed for you to tick; null = the list is closed. */
    data class Backfill(
        val rule: UserRule,
        val items: List<RuleBackfill.Item>,
        val checked: Set<String>,
        val applying: Boolean = false,
        /** The outcome of the last Apply, shown on the list itself since the snackbar is behind it. */
        val notice: String? = null,
    )

    private val ruleSaves = Mutex()

    private val _backfill = MutableStateFlow<Backfill?>(null)
    val backfill: StateFlow<Backfill?> = _backfill.asStateFlow()

    /**
     * Inserts [rule] when its id is 0, otherwise replaces the existing rule with that id. A new rule, or
     * one whose payee text, type or category changed, then lists the older records in Wallet it would
     * change. [fromTx] is the Activity card the rule was made from; its record is in that list too.
     */
    fun saveRule(rule: UserRuleEntity, fromTx: TransactionEntity? = null) = viewModelScope.launch {
        val clean = rule.copy(pattern = rule.pattern.trim())
        // The dialog already blocks this; checked again here in case the rules changed meanwhile.
        // Locked so two quick saves of the same rule can't both pass the check before either writes.
        val (before, id) = ruleSaves.withLock {
            val all = dao.userRules()
            if (all.any { it.id != clean.id && RuleClashes.sameRule(it.toUserRule(), clean.toUserRule()) }) {
                _message.value = "You already have this rule, so nothing was saved."
                return@launch
            }
            all.firstOrNull { it.id == clean.id } to
                if (clean.id == 0L) dao.insertUserRule(clean) else clean.id.also { dao.updateUserRule(clean) }
        }
        if (clean.transferAccountId != null) {
            if (fromTx?.status == TxStatus.SENT) _message.value =
                "Rule saved. Wallet can't turn an existing record into a transfer, so change this one in Wallet."
            return@launch
        }
        val changed = before == null || before.pattern != clean.pattern || before.matchType != clean.matchType ||
            before.appliesTo != clean.appliesTo || before.categoryId != clean.categoryId || before.transferAccountId != null ||
            !before.labelIdList.containsAll(clean.labelIdList)
        if (changed || fromTx != null) findOlderRecords(id, afterSave = true, alsoRecordId = fromTx?.walletRecordId)
    }

    /** Looks up the records in Wallet that the rule with [ruleId] would change, and opens the list. */
    fun findOlderRecords(ruleId: Long, afterSave: Boolean = false, alsoRecordId: String? = null) = viewModelScope.launch {
        val saved = if (afterSave) "Rule saved. " else ""
        val all = dao.userRules()
        val index = all.indexOfFirst { it.id == ruleId }.takeIf { it >= 0 } ?: return@launch
        val rule = all[index].toUserRule()
        if (rule.transferAccountId != null) {
            _message.value = "Wallet can't turn existing records into transfers, so this rule only applies to new ones."
            return@launch
        }
        val token = settings.apiToken ?: run { _message.value = "${saved}Add your Wallet API token to update older records."; return@launch }
        val currencies = settings.seenCurrencies + accounts.value.map { it.currency }
        val accountIds = currencies.mapNotNull { settings.accountFor(it) }.distinct()
        if (accountIds.isEmpty() && alsoRecordId == null) { _message.value = "${saved}Pick a Wallet account in Settings to update older records."; return@launch }
        _busy.value = true
        try {
            val items = RuleBackfill.find(WalletApi(token), all.map { it.toUserRule() }, index, accountIds, alsoRecordId)
            if (items.isEmpty()) _message.value = "${saved}No older records in Wallet need changing."
            else _backfill.value = Backfill(rule, items, items.map { it.recordId }.toSet())
        } catch (e: Exception) {
            _message.value = "${saved}Couldn't look up older records: ${walletError(e)}"
        } finally {
            _busy.value = false
        }
    }

    fun toggleBackfill(recordId: String) = _backfill.update { b ->
        b?.copy(checked = if (recordId in b.checked) b.checked - recordId else b.checked + recordId)
    }

    fun checkAllBackfill(checked: Boolean) = _backfill.update { b ->
        b?.copy(checked = if (checked) b.items.map { it.recordId }.toSet() else emptySet())
    }

    fun closeBackfill() { _backfill.value = null }

    /** Moves the ticked records to the rule's category. Updated ones leave the list; failed ones stay with Wallet's reason. */
    fun applyBackfill() = viewModelScope.launch {
        val b = _backfill.value ?: return@launch
        val token = settings.apiToken ?: return@launch
        val ids = b.items.map { it.recordId }.filter { it in b.checked }
        if (ids.isEmpty() || b.applying) return@launch
        _backfill.value = b.copy(applying = true, notice = null)
        val result = try {
            RuleBackfill.apply(WalletApi(token), b.rule, ids)
        } catch (e: Exception) {
            _backfill.value = b.copy(notice = "Wallet didn't update the records: ${walletError(e)}")
            return@launch
        }
        // Chunked to stay under SQLite's limit on query parameters.
        result.updatedIds.chunked(500).forEach { dao.setRuleCategory(it, b.rule.categoryId, b.rule.categoryName) }
        val done = result.updatedIds.toSet()
        val left = b.items.filterNot { it.recordId in done }
            .map { if (it.recordId in ids) it.copy(error = result.errors[it.recordId]) else it }
        val updated = "Updated ${done.size} of ${ids.size}."
        val notice = when {
            result.retryAfterSeconds != null ->
                "$updated Wallet's hourly limit is reached; try the rest in ${(result.retryAfterSeconds + 59) / 60} min."
            result.stoppedBy != null -> "$updated Then: ${walletError(result.stoppedBy)}"
            result.errors.isNotEmpty() -> "$updated Wallet turned down the rest."
            else -> null
        }
        _backfill.value = if (left.isEmpty()) null else b.copy(items = left, checked = b.checked - done, applying = false, notice = notice)
        if (left.isEmpty()) _message.value = "Updated ${done.size} ${if (done.size == 1) "record" else "records"} in Wallet."
    }

    private fun walletError(e: Exception): String = when (e) {
        is WalletApi.ApiException.Unauthorized -> "Wallet rejected the API token."
        is WalletApi.ApiException.RateLimited -> "Wallet's hourly limit is reached; try again in ${(e.retryAfterSeconds + 59) / 60} min."
        is WalletApi.ApiException.InitialSync -> "Wallet is still preparing API access. Try again in a few minutes."
        else -> e.message ?: e.javaClass.simpleName
    }

    fun deleteRule(rule: UserRuleEntity) = viewModelScope.launch { dao.deleteUserRule(rule.id) }

    /** Drops a learned payee and keeps it out of later re-learns. */
    fun forgetLearned(entry: LearnedEntity) = viewModelScope.launch {
        settings.forgottenPayees = settings.forgottenPayees + entry.payeeKey
        dao.deleteLearned(entry.payeeKey)
    }
}
