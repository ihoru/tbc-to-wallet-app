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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.Scaffold
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
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.displayName
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
private fun App(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(if (vm.settings.hasToken) Tab.ACTIVITY else Tab.SETTINGS) }
    var ruleDraft by remember { mutableStateOf<String?>(null) } // non-null = rule dialog open
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
            if (tab == Tab.RULES) FloatingActionButton(onClick = { ruleDraft = "" }) { Icon(Icons.Default.Add, "Add rule") }
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
                Tab.ACTIVITY -> ActivityScreen(vm, onMakeRule = { ruleDraft = it })
                Tab.RULES -> RulesScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }

    ruleDraft?.let { draft ->
        RuleDialog(vm, initialPattern = draft, onDismiss = { ruleDraft = null })
    }
    if (simulating) FakeNotificationDialog(
        onSubmit = { vm.simulateNotification(it); simulating = false },
        onDismiss = { simulating = false },
    )
}

// ---------------------------------------------------------------- Activity

private val dateFmt = DateTimeFormatter.ofPattern("dd MMM HH:mm")

@Composable
private fun ActivityScreen(vm: MainViewModel, onMakeRule: (String) -> Unit) {
    val txs by vm.transactions.collectAsState()
    if (txs.isEmpty()) {
        EmptyState("No TBC transactions yet.\nThey'll appear here as notifications arrive.\nTap Fake to try one by hand.")
        return
    }
    // Extra bottom space so the Fake button never covers the last card's actions.
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(txs, key = { it.dedupKey }) { tx -> TransactionCard(tx, vm, onMakeRule) }
    }
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
private fun RulesScreen(vm: MainViewModel) {
    val rules by vm.userRules.collectAsState()
    val walletRules by vm.walletRuleCount.collectAsState()
    val learned by vm.learnedCount.collectAsState()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How a category is chosen", style = MaterialTheme.typography.titleSmall)
                    Text("1. Your rules below — always win")
                    Text("2. Wallet's own rules ($walletRules loaded)")
                    Text("3. Learned from your history ($learned payees)")
                    Text("4. Otherwise none — Wallet marks it Unknown")
                    Text(
                        "History is re-read weekly. If you fix a category in Wallet, that correction counts triple next time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (rules.isEmpty()) item { EmptyState("No rules yet. Tap + or use \"Rule…\" on a transaction.") }
        items(rules, key = { it.id }) { rule -> RuleRow(rule, onDelete = { vm.deleteRule(rule) }) }
    }
}

@Composable
private fun RuleRow(rule: UserRuleEntity, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                val how = if (rule.matchType == "EXACT") "is" else "contains"
                val scope = when (rule.appliesTo) { "EXPENSE" -> " · expenses"; "INCOME" -> " · income"; else -> "" }
                Text("Payee $how “${rule.pattern}”$scope", style = MaterialTheme.typography.bodyMedium)
                Text("→ ${rule.categoryName}", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete rule") }
        }
    }
}

@Composable
private fun RuleDialog(vm: MainViewModel, initialPattern: String, onDismiss: () -> Unit) {
    val categories by vm.categories.collectAsState()
    var pattern by remember { mutableStateOf(initialPattern) }
    var matchType by remember { mutableStateOf("CONTAINS") }
    var appliesTo by remember { mutableStateOf("ANY") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CategoryEntity?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category rule") },
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
                if (categories.isEmpty()) {
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
                enabled = pattern.isNotBlank() && selected != null,
                onClick = { vm.addRule(pattern, matchType, appliesTo, selected!!); onDismiss() },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
