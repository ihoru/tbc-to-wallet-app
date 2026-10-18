package su.iho.tbc2wallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.displayName
import su.iho.tbc2wallet.sync.RuleBackfill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val backfillDateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")

/** Full-screen list of older Wallet records a rule would change, all ticked to start with. */
@Composable
internal fun BackfillDialog(
    state: MainViewModel.Backfill,
    categories: List<CategoryEntity>,
    onToggle: (String) -> Unit,
    onCheckAll: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog({ if (!state.applying) onDismiss() }, DialogProperties(usePlatformDefaultWidth = false)) {
        BackfillScreen(state, categories, onToggle, onCheckAll, onApply, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackfillScreen(
    state: MainViewModel.Backfill,
    categories: List<CategoryEntity>,
    onToggle: (String) -> Unit,
    onCheckAll: (Boolean) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val byId = categories.associateBy { it.id }
    val newName = byId[state.rule.categoryId]?.displayName ?: state.rule.categoryName
    val ticked = state.items.count { it.recordId in state.checked }
    val allTicked = ticked == state.items.size
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Update older records") },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !state.applying) { Icon(Icons.Default.Close, "Close") }
                },
            )
            Text(
                "These records in Wallet from the last ${RuleBackfill.MONTHS} months match “${state.rule.pattern}”. " +
                    "Ticked ones move to $newName" +
                    state.rule.labelIds.ifEmpty { null }?.let { " and get the rule's labels" }.orEmpty() + ".",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$ticked of ${state.items.size} selected", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onCheckAll(!allTicked) }, enabled = !state.applying) {
                    Text(if (allTicked) "Clear all" else "Select all")
                }
            }
            state.notice?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.applying) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(state.items, key = { it.recordId }) { item ->
                    BackfillRow(
                        item, checked = item.recordId in state.checked, enabled = !state.applying,
                        oldName = item.categoryId?.let { byId[it]?.displayName } ?: item.categoryName ?: "Unknown",
                        onToggle = { onToggle(item.recordId) },
                    )
                    HorizontalDivider()
                }
            }
            Button(
                onClick = onApply,
                enabled = ticked > 0 && !state.applying,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) { Text(if (state.applying) "Applying…" else "Apply ($ticked)") }
        }
    }
}

@Composable
private fun BackfillRow(
    item: RuleBackfill.Item, checked: Boolean, enabled: Boolean, oldName: String, onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row toggles, so the box itself takes no clicks of its own.
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.payee, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                item.amount?.let { Text(formatAmount(it, item.currency), style = MaterialTheme.typography.titleSmall) }
            }
            val date = item.recordDate?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(backfillDateFmt) }.getOrNull() }
            Text(listOfNotNull(date, oldName).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            item.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatAmount(value: Double, currency: String?): String {
    val sign = if (value < 0) "−" else "+"
    return "$sign${String.format(Locale.US, "%.2f", kotlin.math.abs(value))} ${currency.orEmpty()}".trim()
}
