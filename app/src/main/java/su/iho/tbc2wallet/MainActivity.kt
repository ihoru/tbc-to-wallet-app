package su.iho.tbc2wallet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.LifecycleResumeEffect
import su.iho.tbc2wallet.categorize.CategoryTree
import su.iho.tbc2wallet.data.AccountEntity
import su.iho.tbc2wallet.categorize.CategoryResolver
import su.iho.tbc2wallet.categorize.RuleClash
import su.iho.tbc2wallet.categorize.RuleClashes
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.LearnedEntity
import su.iho.tbc2wallet.data.displayName
import su.iho.tbc2wallet.data.labelIdList
import su.iho.tbc2wallet.data.labelNameList
import su.iho.tbc2wallet.data.toUserRule
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.sync.SyncWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SyncWorker.schedulePeriodic(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(vm)
            }
        }
    }
}

private enum class Tab(val label: String) { ACTIVITY("Activity"), RULES("Rules"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun App(vm: MainViewModel) { // internal for the screenshot tests
    var tab by rememberSaveable { mutableStateOf(if (vm.settings.hasToken) Tab.ACTIVITY else Tab.SETTINGS) }
    var ruleDraft by remember { mutableStateOf<UserRuleEntity?>(null) } // non-null = rule dialog open; id 0 = new rule
    var simulating by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TBC → Wallet") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t }, label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) { Tab.ACTIVITY -> Icons.AutoMirrored.Filled.List; Tab.RULES -> Icons.Default.Edit; Tab.SETTINGS -> Icons.Default.Settings },
                                contentDescription = null,
                            )
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.RULES) FloatingActionButton(onClick = { ruleDraft = newRule("") }) { Icon(Icons.Default.Add, "Add rule") }
            if (tab == Tab.ACTIVITY) ExtendedFloatingActionButton(
                onClick = { simulating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Fake") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                Tab.ACTIVITY -> ActivityScreen(vm, onMakeRule = { ruleDraft = newRule(it) })
                Tab.RULES -> RulesScreen(vm, onEdit = { ruleDraft = it })
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }

    ruleDraft?.let { draft ->
        RuleDialog(vm, initial = draft, onDismiss = { ruleDraft = null })
    }
    if (simulating) FakeNotificationDialog(
        onSubmit = { vm.simulateNotification(it); simulating = false },
        onDismiss = { simulating = false },
    )
}

private fun newRule(pattern: String) =
    UserRuleEntity(pattern = pattern, matchType = "CONTAINS", appliesTo = "ANY", categoryId = "", categoryName = "")

// ---------------------------------------------------------------- Activity

private val dateFmt = DateTimeFormatter.ofPattern("dd MMM HH:mm")

@Composable
private fun ActivityScreen(vm: MainViewModel, onMakeRule: (String) -> Unit) {
    val txs by vm.transactions.collectAsState()
    if (txs.isEmpty()) {
        EmptyState("No TBC transactions yet.\nThey'll appear here as notifications arrive.\nTap Fake to try one by hand.")
        return
    }
    var deleting by remember { mutableStateOf<TransactionEntity?>(null) }
    // Extra bottom space so the Fake button never covers the last card's actions.
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(txs, key = { it.dedupKey }) { tx ->
            SwipeToDelete(onSwiped = { deleting = tx }) { TransactionCard(tx, vm, onMakeRule) }
        }
    }
    deleting?.let { tx ->
        DeleteTransactionDialog(tx, onConfirm = { vm.deleteTransaction(tx); deleting = null }, onDismiss = { deleting = null })
    }
}

/** Swiping either way asks [onSwiped] to confirm; the card springs back until the row is really gone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) onSwiped()
        false
    })
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val start = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, CardDefaults.shape).padding(horizontal = 20.dp),
                contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@Composable
private fun DeleteTransactionDialog(tx: TransactionEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val what = tx.counterParty ?: if (tx.status == TxStatus.UNPARSED) "this unreadable notification" else "this transaction"
    val consequence = when {
        tx.walletRecordId != null -> "The record already in Wallet stays there; delete it in Wallet if you want it gone too."
        tx.status in setOf(TxStatus.PENDING, TxStatus.NEEDS_ACCOUNT, TxStatus.FAILED) -> "It hasn't reached Wallet, and now it won't be sent."
        else -> "Nothing changes in Wallet."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete from this list?") },
        text = { Text("This removes $what from the app's activity list only. $consequence") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TransactionCard(tx: TransactionEntity, vm: MainViewModel, onMakeRule: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (tx.status == TxStatus.UNPARSED) {
                Text("Couldn't read this notification", style = MaterialTheme.typography.titleSmall)
                Text(tx.rawText, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tx.counterParty ?: if (tx.kind == "INCOME") "Incoming transfer" else "Card payment",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val sign = if (tx.kind == "EXPENSE") "−" else "+"
                    Text("$sign${tx.amount} ${tx.currency}", style = MaterialTheme.typography.titleMedium)
                }
                val date = tx.recordDateUtc?.let { Instant.parse(it).atZone(ZoneId.systemDefault()).format(dateFmt) }.orEmpty()
                Text("$date · ${tx.accountLabel.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                Text(categoryLine(tx), style = MaterialTheme.typography.bodySmall)
            }
            if (tx.isFake) Text(
                "Fake · typed in by hand, not from TBC",
                color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium,
            )
            tx.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(statusLabel(tx.status)) })
                Spacer(Modifier.weight(1f))
                if (tx.status == TxStatus.FAILED) TextButton(onClick = { vm.retry(tx) }) { Text("Retry") }
                if (tx.status in setOf(TxStatus.FAILED, TxStatus.UNPARSED, TxStatus.NEEDS_ACCOUNT, TxStatus.PENDING))
                    TextButton(onClick = { vm.ignore(tx) }) { Text("Ignore") }
                if (tx.counterParty != null) TextButton(onClick = { onMakeRule(tx.counterParty) }) { Text("Rule…") }
            }
        }
    }
}

private fun categoryLine(tx: TransactionEntity): String = when (tx.categorySource) {
    null -> "Category: decided when sent"
    "NONE" -> "Category: none (Wallet files it as Unknown)"
    "USER_RULE" -> "Category: ${tx.categoryName} · your rule"
    "WALLET_RULE" -> "Category: ${tx.categoryName} · Wallet rule"
    "LEARNED" -> "Category: ${tx.categoryName} · learned from history"
    SyncWorker.TRANSFER_SOURCE ->
        (if (tx.kind == "EXPENSE") "Transfer to " else "Transfer from ") + "${tx.categoryName} · your rule"
    else -> "Category: ${tx.categoryName}"
}

private fun statusLabel(s: TxStatus) = when (s) {
    TxStatus.PENDING -> "Queued"
    TxStatus.NEEDS_ACCOUNT -> "Needs account"
    TxStatus.SENT -> "In Wallet"
    TxStatus.FAILED -> "Failed"
    TxStatus.UNPARSED -> "Unreadable"
    TxStatus.IGNORED -> "Ignored"
}

/** A purchase dated now in Tbilisi time, so the simulated record lands in today's Wallet activity. */
private fun fakeSample(): String {
    val now = java.time.ZonedDateTime.now(su.iho.tbc2wallet.ingest.Ingest.BANK_ZONE)
    val stamp = now.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    return "42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy $stamp\nBalance: 1500.00 GEL"
}

@Composable
private fun FakeNotificationDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fake notification") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste or type the text of a TBC notification. It goes through the parser and rules " +
                        "like a real one, then goes to Wallet marked [fake] in the note and, when Wallet allows it, " +
                        "with the \"fake\" label.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Notification text") },
                    placeholder = { Text(fakeSample(), fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = fakeSample() }) { Text("Use sample") }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onSubmit(text) }) { Text("Simulate") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Rules

@Composable
private fun RulesScreen(vm: MainViewModel, onEdit: (UserRuleEntity) -> Unit) {
    val rules by vm.userRules.collectAsState()
    val walletRules by vm.walletRuleCount.collectAsState()
    val learned by vm.learned.collectAsState()
    var learnedOpen by rememberSaveable { mutableStateOf(false) }
    val clashes = remember(rules) { RuleClashes.find(rules.sortedBy { it.id }) { it.toUserRule() } }
    val clashing = remember(clashes) { clashes.flatMap { listOf(it.first.id, it.second.id) }.toSet() }
    // Extra bottom space so the + button never covers the last row.
    LazyColumn(
        contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How a category is chosen", style = MaterialTheme.typography.titleSmall)
                    Text("1. Your rules below — always win")
                    Text("2. Wallet's own rules ($walletRules loaded)")
                    Text("3. Learned from your history (${learned.size} payees)")
                    Text("4. Otherwise none — Wallet marks it Unknown")
                    Text(
                        "History is re-read weekly. If you fix a category in Wallet, that correction counts triple next time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (rules.isEmpty()) item { EmptyState("No rules yet. Tap + or use \"Rule…\" on a transaction.") }
        else item { Text("Tap a rule to edit it.", style = MaterialTheme.typography.bodySmall) }
        items(clashes, key = { "clash-${it.first.id}-${it.second.id}" }) { clash ->
            ClashCard(clash, onKeep = { keep -> vm.deleteRule(if (keep == clash.first) clash.second else clash.first) }, onEdit = onEdit)
        }
        items(rules, key = { it.id }) { rule ->
            RuleRow(rule, clashing = rule.id in clashing, onEdit = { onEdit(rule) }, onDelete = { vm.deleteRule(rule) })
        }
        if (learned.isNotEmpty()) {
            item(key = "learned-header") {
                Row(
                    Modifier.fillMaxWidth().clickable { learnedOpen = !learnedOpen }.padding(top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Learned from your history (${learned.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(
                        if (learnedOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        if (learnedOpen) "Collapse" else "Expand",
                    )
                }
            }
            if (learnedOpen) {
                item(key = "learned-hint") {
                    Text(
                        "Rebuilt from Wallet every week. Tap one to turn it into your own rule.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(learned, key = { "learned-" + it.payeeKey }) { entry ->
                    LearnedRow(entry, onClick = {
                        onEdit(newRule(entry.payeeKey).copy(
                            matchType = "EXACT", categoryId = entry.categoryId, categoryName = entry.categoryName,
                        ))
                    })
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: UserRuleEntity, clashing: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                val how = if (rule.matchType == "EXACT") "is" else "contains"
                val scope = when (rule.appliesTo) { "EXPENSE" -> " · expenses"; "INCOME" -> " · income"; else -> "" }
                Text("Payee $how “${rule.pattern}”$scope", style = MaterialTheme.typography.bodyMedium)
                val labels = rule.labelNameList.joinToString("") { " · #$it" }
                Text("→ ${ruleTarget(rule)}$labels", style = MaterialTheme.typography.bodySmall)
            }
            if (clashing) Icon(Icons.Default.Warning, "Clashes with another rule", tint = MaterialTheme.colorScheme.error)
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete rule") }
        }
    }
}

private fun ruleSummary(rule: UserRuleEntity): String {
    val how = if (rule.matchType == "EXACT") "is" else "contains"
    val scope = when (rule.appliesTo) { "EXPENSE" -> " for expenses"; "INCOME" -> " for income"; else -> "" }
    return "payee $how “${rule.pattern}”$scope"
}

private fun ruleTarget(rule: UserRuleEntity) = rule.transferAccountName?.let { "Transfer with $it" } ?: rule.categoryName

private fun ruleOutcome(rule: UserRuleEntity) = ruleTarget(rule) + rule.labelNameList.joinToString("") { " #$it" }

/**
 * Short name for a rule on a clash button. Adds "exact" and the scope when set, and the category
 * and labels if that's still not enough, so the two buttons never read the same.
 */
private fun clashButtonName(rule: UserRuleEntity, other: UserRuleEntity): String {
    fun base(r: UserRuleEntity) = "“${r.pattern}”" + listOfNotNull(
        "exact".takeIf { r.matchType == "EXACT" },
        when (r.appliesTo) { "EXPENSE" -> "expenses"; "INCOME" -> "income"; else -> null },
    ).takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty()
    val name = base(rule)
    return if (name.equals(base(other), ignoreCase = true)) "$name → ${ruleOutcome(rule)}" else name
}

/** Two rules that can match the same payee but disagree; only [RuleClash.winner] is applied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClashCard(clash: RuleClash<UserRuleEntity>, onKeep: (UserRuleEntity) -> Unit, onEdit: (UserRuleEntity) -> Unit) {
    val (a, b) = clash.first to clash.second
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Warning, null)
                Text("These rules clash", style = MaterialTheme.typography.titleSmall)
            }
            Text("• ${ruleSummary(a).replaceFirstChar { it.uppercase() }} → ${ruleOutcome(a)}", style = MaterialTheme.typography.bodySmall)
            Text("• ${ruleSummary(b).replaceFirstChar { it.uppercase() }} → ${ruleOutcome(b)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "A payee matching both gets ${ruleOutcome(clash.winner)}, from the rule where ${ruleSummary(clash.winner)}.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (rule in listOf(a, b)) {
                FlowRow {
                    TextButton(onClick = { onKeep(rule) }) { Text("Keep only ${clashButtonName(rule, other = if (rule == a) b else a)}") }
                    TextButton(onClick = { onEdit(rule) }) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun LearnedRow(entry: LearnedEntity, onClick: () -> Unit) {
    val used = entry.votes >= CategoryResolver.MIN_VOTES &&
        entry.votes.toDouble() / entry.totalVotes >= CategoryResolver.MIN_SHARE
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("Payee is “${entry.payeeKey}”", style = MaterialTheme.typography.bodyMedium)
            Text(
                "→ ${entry.categoryName} · ${entry.votes} of ${entry.totalVotes} votes" + if (used) "" else " · not used yet",
                style = MaterialTheme.typography.bodySmall,
                color = if (used) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(vm: MainViewModel, initial: UserRuleEntity, onDismiss: () -> Unit) {
    val categories by vm.categories.collectAsState()
    val labels by vm.labels.collectAsState()
    val accounts by vm.accounts.collectAsState()
    // A prefilled transfer account (id + name) is kept even if it's no longer in the loaded list.
    var transferAccount by remember {
        mutableStateOf(initial.transferAccountId?.let { it to (initial.transferAccountName ?: it) })
    }
    var asTransfer by remember { mutableStateOf(initial.transferAccountId != null) }
    // label id -> name; starts from the rule so its labels survive even if Wallet's list isn't loaded.
    var chosenLabels by remember { mutableStateOf(initial.labelIdList.zip(initial.labelNameList).toMap()) }
    val editing = initial.id != 0L
    var pattern by remember { mutableStateOf(initial.pattern) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var appliesTo by remember { mutableStateOf(initial.appliesTo) }
    var query by remember { mutableStateOf("") }
    // A prefilled category (id + name) is kept even if it's no longer in the loaded list.
    var selected by remember {
        mutableStateOf(initial.categoryId.takeIf { it.isNotEmpty() }?.let { CategoryEntity(it, initial.categoryName, null) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit rule" else "New rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pattern, { pattern = it }, label = { Text("Payee text") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(matchType == "CONTAINS", { matchType = "CONTAINS" }, label = { Text("Contains") })
                    FilterChip(matchType == "EXACT", { matchType = "EXACT" }, label = { Text("Exact") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ANY" to "Any", "EXPENSE" to "Expenses", "INCOME" to "Income").forEach { (v, l) ->
                        FilterChip(appliesTo == v, { appliesTo = v }, label = { Text(l) })
                    }
                }
                Text("Record as", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!asTransfer, { asTransfer = false }, label = { Text("Category") })
                    FilterChip(asTransfer, { asTransfer = true }, label = { Text("Transfer") })
                }
                Text("Labels", style = MaterialTheme.typography.labelLarge)
                // Labels on the rule that Wallet no longer lists still show, so they can be removed.
                val shown = labels.map { it.id to it.name } + chosenLabels.filterKeys { id -> labels.none { it.id == id } }.toList()
                if (shown.isEmpty()) {
                    Text("No labels loaded from Wallet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    // Capped and scrollable so a long label list can't push the category picker out of the dialog.
                    FlowRow(
                        Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        shown.forEach { (id, name) ->
                            FilterChip(
                                selected = id in chosenLabels,
                                onClick = { chosenLabels = if (id in chosenLabels) chosenLabels - id else chosenLabels + (id to name) },
                                label = { Text(name) },
                            )
                        }
                    }
                }
                if (asTransfer) {
                    // Wallet creates the other side of the transfer there, which it won't do in a bank-synced account.
                    TransferAccountPicker(accounts.filterNot { it.isBankSync }, transferAccount) { transferAccount = it }
                    Text(
                        "An expense becomes a transfer to this account, income a transfer from it.",
                        style = MaterialTheme.typography.bodySmall,
                        // As wide as the field above; a long line would otherwise widen the whole dialog.
                        modifier = Modifier.widthIn(max = OutlinedTextFieldDefaults.MinWidth),
                    )
                } else if (categories.isEmpty() && selected == null) {
                    Text("No categories loaded. Add your token in Settings and tap Refresh.", color = MaterialTheme.colorScheme.error)
                } else {
                    OutlinedTextField(
                        value = selected?.displayName ?: query,
                        onValueChange = { query = it; selected = null },
                        label = { Text("Category") }, singleLine = true,
                    )
                    if (selected == null) CategoryTreeList(categories, query, onSelect = { selected = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank() && if (asTransfer) transferAccount != null else selected != null,
                onClick = {
                    val transfer = transferAccount.takeIf { asTransfer }
                    vm.saveRule(initial.copy(
                        pattern = pattern, matchType = matchType, appliesTo = appliesTo,
                        categoryId = if (transfer == null) selected!!.id else "",
                        categoryName = if (transfer == null) selected!!.displayName else "",
                        transferAccountId = transfer?.first,
                        // Current "Name (CUR)" from Wallet, so rules saved before the currency was added pick it up.
                        transferAccountName = transfer?.let { (id, name) -> accounts.firstOrNull { it.id == id }?.let(::accountLabel) ?: name },
                        labelIds = chosenLabels.keys.joinToString("\n"),
                        // Prefer Wallet's current name in case a label was renamed since the rule was saved.
                        labelNames = chosenLabels.map { (id, name) -> labels.firstOrNull { it.id == id }?.name ?: name }.joinToString("\n"),
                    ))
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (editing) TextButton(onClick = { vm.deleteRule(initial); onDismiss() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Categories as a tree: parents collapsed by default, tap the arrow to show subcategories.
 * While searching, every parent with a match is shown expanded.
 */
@Composable
private fun CategoryTreeList(categories: List<CategoryEntity>, query: String, onSelect: (CategoryEntity) -> Unit) {
    val tree = remember(categories) { CategoryTree.build(categories) }
    val shown = remember(tree, query) { CategoryTree.filter(tree, query) }
    val searching = query.isNotBlank()
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    LazyColumn(Modifier.heightIn(max = 260.dp)) {
        shown.forEach { node ->
            val open = searching || node.name in expanded
            item(key = "p:${node.name}") {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        val c = node.category
                        if (c != null) onSelect(c)
                        else expanded = if (node.name in expanded) expanded - node.name else expanded + node.name
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.children.isEmpty()) Spacer(Modifier.width(48.dp))
                    else IconButton(
                        onClick = { expanded = if (node.name in expanded) expanded - node.name else expanded + node.name },
                        enabled = !searching,
                    ) {
                        Icon(
                            if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            if (open) "Collapse ${node.name}" else "Expand ${node.name}",
                        )
                    }
                    Text(
                        node.name,
                        Modifier.weight(1f).padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (node.category == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (node.children.isNotEmpty()) Text(
                        "${node.children.size}",
                        Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
            if (open) items(node.children, key = { it.id }) { c ->
                Text(
                    c.name,
                    Modifier.fillMaxWidth().clickable { onSelect(c) }.padding(start = 48.dp, top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider(Modifier.padding(start = 48.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Settings

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accounts by vm.accounts.collectAsState()
    val version by vm.settingsVersion.collectAsState()
    var hasAccess by remember { mutableStateOf(vm.hasNotificationAccess()) }
    var canAlert by remember { mutableStateOf(canPostNotifications(context)) }
    LifecycleResumeEffect(Unit) {
        hasAccess = vm.hasNotificationAccess()
        canAlert = canPostNotifications(context)
        onPauseOrDispose {}
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canAlert = it }
    // Steps are numbered from 1 whichever ones are shown; the access step disappears once everything is granted.
    val showAccess = !hasAccess || !canAlert
    val step = if (showAccess) 1 else 0

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showAccess) Section("1. Notification access") {
            if (!hasAccess) {
                Text("Needed to read TBC notifications.")
                Button(onClick = { context.startActivity(Intent(ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Open notification access")
                }
            }
            if (!canAlert) OutlinedButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow alerts when something needs attention")
            }
        }

        Section("${step + 1}. Wallet API token") {
            var token by remember { mutableStateOf("") }
            Text(
                if (vm.settings.hasToken) "Token saved (encrypted on this phone)." else
                    "Create one in Wallet web (Premium plan), then paste it here.",
                style = MaterialTheme.typography.bodySmall,
            )
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(
                onClick = { uriHandler.openUri("https://web.budgetbakers.com/settings/rest-api/create-token") },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Get a token on Wallet web") }
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(if (vm.settings.hasToken) "Replace token" else "Paste token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = token.isNotBlank(), onClick = { vm.saveToken(token); token = "" }) { Text("Save & connect") }
                OutlinedButton(enabled = vm.settings.hasToken, onClick = { vm.refreshFromWallet(learn = true) }) { Text("Refresh from Wallet") }
            }
        }

        Section("${step + 2}. Where each currency goes") {
            key(version) {
                val currencies = vm.settings.seenCurrencies.sorted()
                if (accounts.isEmpty()) Text("Connect your token to load accounts.", style = MaterialTheme.typography.bodySmall)
                else currencies.forEach { cur ->
                    AccountPicker(cur, accounts.filter { it.currency == cur || it.currency.isBlank() }, vm.settings.accountFor(cur)) {
                        vm.setAccount(cur, it)
                    }
                }
                val synced = accounts.filter { it.isBankSync && it.id in currencies.mapNotNull(vm.settings::accountFor) }
                if (synced.isNotEmpty()) Text(
                    "“${synced.joinToString { it.name }}” is bank-synced — transactions would be duplicated there. Use a manual account.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
                Text("New currencies appear here after the first notification in that currency.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Advanced") {
            key(version) {
                var pkg by remember { mutableStateOf(vm.settings.tbcPackage) }
                OutlinedTextField(
                    pkg, { pkg = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("TBC app package") },
                    supportingText = { Text("Leave blank to detect it from the first TBC notification.") },
                )
                OutlinedButton(onClick = { vm.setTbcPackage(pkg) }) { Text("Save package") }
            }
            key(version) { Text("Last sync: ${vm.settings.lastSyncMessage ?: "never"}", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { vm.syncNow() }) { Text("Sync now") }
        }
    }
}

private fun canPostNotifications(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountPicker(currency: String, options: List<AccountEntity>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: "Not set", onValueChange = {}, readOnly = true,
            label = { Text(currency) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text("No $currency accounts in Wallet") }, onClick = { expanded = false })
            options.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(acc.name + if (acc.isBankSync) "  (bank-synced)" else "") },
                    onClick = { onSelect(acc.id); expanded = false },
                )
            }
            if (selected != null) DropdownMenuItem(text = { Text("Clear") }, onClick = { onSelect(null); expanded = false })
        }
    }
}

/** A Wallet account for the other side of a transfer. [selected] is (id, name). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferAccountPicker(
    options: List<AccountEntity>, selected: Pair<String, String>?, onSelect: (Pair<String, String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = selected?.second ?: "Choose account", onValueChange = {}, readOnly = true,
            label = { Text("Other account") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            // Default text field width, like the dialog's other fields, so switching modes doesn't resize it.
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            if (options.isEmpty()) DropdownMenuItem(
                text = { Text("No manual accounts loaded. Add your token in Settings and tap Refresh.") },
                onClick = { expanded = false },
            )
            options.forEach { acc ->
                DropdownMenuItem(
                    text = { Text(accountLabel(acc)) },
                    onClick = { onSelect(acc.id to accountLabel(acc)); expanded = false },
                )
            }
        }
    }
}

/** Name with currency, so two accounts with the same name ("Cash") stay distinguishable. */
private fun accountLabel(acc: AccountEntity) = "${acc.name} (${acc.currency})"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
