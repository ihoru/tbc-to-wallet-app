package su.iho.tbc2wallet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import su.iho.tbc2wallet.categorize.CategoryNode
import su.iho.tbc2wallet.categorize.CategoryTree
import su.iho.tbc2wallet.data.CategoryEntity

/**
 * Full-screen category picker laid out like Wallet's: "All categories" lists the groups;
 * opening one shows it under "General" (to pick the group itself) and its categories under
 * "Subcategories", each with a count of its own subcategories. The search icon lists every match.
 * Your most used categories sit above the groups: a row of a few, which opens into a full list.
 */
@Composable
internal fun CategoryPickerDialog(
    categories: List<CategoryEntity>,
    onPick: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
    frequent: List<CategoryEntity> = emptyList(),
) {
    // Fits the system bars, so the last rows aren't drawn under the navigation bar.
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        CategoryPicker(categories, onPick, onDismiss, frequent = frequent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPicker(
    categories: List<CategoryEntity>,
    onPick: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit,
    initialPath: List<String> = emptyList(),
    initialQuery: String? = null,
    frequent: List<CategoryEntity> = emptyList(),
    initialFrequentOpen: Boolean = false,
) {
    val tree = remember(categories) { CategoryTree.build(categories) }
    // The tree's row for each frequent category, so it gets the same color and glyph as in its group.
    val frequentRows = remember(tree, frequent) {
        val byId = mutableMapOf<String, Pair<CategoryNode, String?>>()
        fun walk(nodes: List<CategoryNode>, above: List<String>) {
            for (n in nodes) {
                n.category?.let { byId.putIfAbsent(it.id, n.copy(children = emptyList()) to above.takeIf { it.isNotEmpty() }?.joinToString(" / ")) }
                walk(n.children, above + n.name)
            }
        }
        walk(tree, emptyList())
        frequent.mapNotNull { byId[it.id] }.take(FREQUENT_MAX)
    }
    var frequentOpen by remember { mutableStateOf(initialFrequentOpen) }
    // Keys of the rows opened so far, outermost first.
    var path by remember { mutableStateOf(initialPath) }
    var query by remember { mutableStateOf(initialQuery) }
    val stack = remember(tree, path) {
        path.runningFold(null as CategoryNode?) { parent, key ->
            (parent?.children ?: tree).firstOrNull { it.key == key }
        }.drop(1).filterNotNull()
    }
    val current = stack.lastOrNull()
    val back = {
        when {
            query != null -> query = null
            path.isNotEmpty() -> path = path.dropLast(1)
            else -> onDismiss()
        }
    }
    BackHandler(onBack = back)
    val searchFocus = remember { FocusRequester() }
    val searching = query != null
    LaunchedEffect(searching) { if (searching) searchFocus.requestFocus() }
    val open = { n: CategoryNode ->
        if (n.children.isNotEmpty()) path = path + n.key else n.category?.let(onPick)
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val q = query
                    if (q == null) Text(current?.name ?: "Category")
                    else TextField(
                        q, { query = it },
                        // Tapping search goes straight to typing: focus the field, which opens the keyboard.
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        placeholder = { Text("Search categories") }, singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                },
                actions = {
                    if (query == null) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Search, "Search") }
                },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                val q = query
                when {
                    q != null -> {
                        val hits = searchRows(tree, q)
                        items(hits, key = { it.first.key }) { (n, parentPath) ->
                            CategoryRow(n, subtitle = parentPath, onClick = { n.category?.let(onPick) ?: open(n) })
                        }
                    }
                    current == null -> {
                        if (frequentRows.isNotEmpty()) {
                            val more = frequentRows.size > FREQUENT_ROW
                            section("Frequently used", action = if (!more) null else if (frequentOpen) "Show less" else "Show all") {
                                frequentOpen = !frequentOpen
                            }
                            if (frequentOpen) items(frequentRows, key = { "frequent:" + it.first.key }) { (n, parentPath) ->
                                CategoryRow(n, subtitle = parentPath, onClick = { n.category?.let(onPick) })
                            }
                            else item(key = "frequent") { FrequentStrip(frequentRows.take(FREQUENT_ROW).map { it.first }, onPick) }
                        }
                        section("All categories")
                        items(tree, key = { it.key }) { n -> CategoryRow(n, onClick = { open(n) }) }
                    }
                    else -> {
                        current.category?.let { own ->
                            section("General")
                            item(key = "general") { CategoryRow(current.copy(children = emptyList()), onClick = { onPick(own) }) }
                        }
                        section("Subcategories")
                        // Wallet adds a "Parent / Child" line once you're below the group's categories.
                        val sub = if (stack.size >= 2) stack.joinToString(" / ") { it.name } else null
                        items(current.children, key = { it.key }) { n ->
                            CategoryRow(n, subtitle = sub, count = n.children.size.takeIf { it > 0 }, onClick = { open(n) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every pickable row matching [query], with the names above it ("Food & Drinks / Bar, cafe").
 * A row also matches when a row above it does, so "Income" lists everything in that group.
 */
internal fun searchRows(tree: List<CategoryNode>, query: String): List<Pair<CategoryNode, String?>> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = mutableListOf<Pair<CategoryNode, String?>>()
    fun walk(nodes: List<CategoryNode>, above: List<String>, aboveMatched: Boolean) {
        for (n in nodes) {
            val matched = aboveMatched || n.name.contains(q, ignoreCase = true)
            if (n.category != null && matched) {
                out += n.copy(children = emptyList()) to above.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            }
            walk(n.children, above + n.name, matched)
        }
    }
    walk(tree, emptyList(), false)
    return out
}

/** How many frequent categories the row shows, and the most the full list does. */
private const val FREQUENT_ROW = 4
private const val FREQUENT_MAX = 20

private fun LazyListScope.section(title: String, action: String? = null, onAction: () -> Unit = {}) = item(key = "section:$title") {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(), Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let {
            Text(
                it.uppercase(),
                Modifier.clickable(onClick = onAction).padding(vertical = 12.dp, horizontal = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}

/** A few of your most used categories side by side, each a badge over its name; nothing to scroll. */
@Composable
private fun FrequentStrip(nodes: List<CategoryNode>, onPick: (CategoryEntity) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        nodes.forEach { n ->
            Column(
                Modifier.weight(1f).clickable { n.category?.let(onPick) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CategoryIcon(n.iconKey, n.color, 42.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    n.name, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Keeps a short row's tiles the same width as a full one's.
        repeat(FREQUENT_ROW - nodes.size) { Spacer(Modifier.weight(1f)) }
    }
    HorizontalDivider()
}

@Composable
private fun CategoryRow(node: CategoryNode, onClick: () -> Unit, subtitle: String? = null, count: Int? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryIcon(node.iconKey, node.color, 42.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        count?.let { Text("($it)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider()
}

/** Wallet's round category badge: a white glyph on the category's color. */
@Composable
internal fun CategoryIcon(iconKey: String, hex: String?, size: Dp) {
    val bg = remember(hex) { runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
        ?: MaterialTheme.colorScheme.outline
    Box(Modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(categoryIcon(iconKey), null, Modifier.size(size * 0.55f), tint = Color.White)
    }
}

/**
 * The Wallet API sends no icons, so pick a close Material glyph from the category's system id
 * (or name) and fall back to its group's glyph, the way Wallet draws custom subcategories.
 */
internal fun categoryIcon(key: String): ImageVector {
    val k = key.lowercase()
    // A group's own key is its slug; match it whole, since words like "sport" hide inside "transportation".
    // System ids are "<group>__<category>"; only the category part picks the glyph.
    val own = k.substringAfter("__")
    return GROUP_ICONS[k] ?: ICONS.firstOrNull { (words, _) -> words.any { it in own } }?.second
        ?: GROUP_ICONS[k.substringBefore("__")] ?: Icons.Default.MoreHoriz
}

private val ICONS: List<Pair<List<String>, ImageVector>> = listOf(
    // Categories first: their system ids also contain the group slug.
    listOf("groceries") to Icons.Default.LocalGroceryStore,
    listOf("restaurant", "fast-food", "fast_food") to Icons.Default.RoomService,
    listOf("bar", "cafe") to Icons.Default.WineBar,
    listOf("clothes", "shoes") to Icons.Default.Checkroom,
    listOf("drug", "chemist", "pharmacy") to Icons.Default.LocalPharmacy,
    listOf("electronics", "accessories") to Icons.Default.Devices,
    listOf("gifts", "joy") to Icons.Default.CardGiftcard,
    listOf("health_and_beauty", "health & beauty", "wellness", "beauty") to Icons.Default.Spa,
    listOf("home", "garden") to Icons.Default.Yard,
    listOf("jewels") to Icons.Default.Diamond,
    listOf("kids") to Icons.Default.ChildCare,
    listOf("pets", "animals") to Icons.Default.Pets,
    listOf("stationery", "tools") to Icons.Default.Build,
    listOf("energy", "utilities") to Icons.Default.Bolt,
    listOf("vehicle_maintenance") to Icons.Default.CarRepair,
    listOf("maintenance", "repairs") to Icons.Default.Handyman,
    listOf("mortgage") to Icons.Default.AccountBalance,
    listOf("property_insurance", "property insurance", "vehicle_insurance", "vehicle insurance") to Icons.Default.Security,
    listOf("leasing", "rentals") to Icons.Default.CarRental,
    listOf("rental_income", "rental income", "rent") to Icons.Default.Key,
    listOf("business_trips", "business trips") to Icons.Default.BusinessCenter,
    listOf("long_distance", "long distance") to Icons.Default.Flight,
    listOf("public_transport", "public transport") to Icons.Default.DirectionsBus,
    listOf("taxi") to Icons.Default.LocalTaxi,
    listOf("fuel") to Icons.Default.LocalGasStation,
    listOf("parking") to Icons.Default.LocalParking,
    listOf("sport", "fitness") to Icons.Default.FitnessCenter,
    listOf("alcohol", "tobacco") to Icons.Default.SmokingRooms,
    listOf("books", "audio", "subscriptions") to Icons.Default.Book,
    listOf("charity") to Icons.Default.VolunteerActivism,
    listOf("life_events", "life events") to Icons.Default.Cake,
    listOf("culture", "events") to Icons.Default.TheaterComedy,
    listOf("education", "development") to Icons.Default.School,
    listOf("health_care", "health care", "doctor") to Icons.Default.LocalHospital,
    listOf("hobbies") to Icons.Default.Palette,
    listOf("holiday", "trips", "hotels") to Icons.Default.Hotel,
    listOf("lottery", "gambling") to Icons.Default.Casino,
    listOf("tv", "streaming") to Icons.Default.Tv,
    listOf("internet") to Icons.Default.Wifi,
    listOf("phone", "cell") to Icons.Default.PhoneAndroid,
    listOf("postal") to Icons.Default.LocalPostOffice,
    listOf("software", "apps", "games") to Icons.Default.SportsEsports,
    listOf("advisory") to Icons.Default.SupportAgent,
    listOf("charges", "fees") to Icons.Default.Receipt,
    listOf("child_support", "child support") to Icons.Default.FamilyRestroom,
    listOf("fines") to Icons.Default.Gavel,
    listOf("insurances") to Icons.Default.Shield,
    listOf("loan", "interests", "dividends") to Icons.Default.Percent,
    listOf("taxes") to Icons.Default.AccountBalance,
    listOf("collections") to Icons.Default.Collections,
    listOf("financial_investments", "financial investments") to Icons.Default.Insights,
    listOf("realty") to Icons.Default.Apartment,
    listOf("savings") to Icons.Default.Savings,
    listOf("vehicles", "chattels") to Icons.Default.DirectionsCar,
    listOf("wage", "invoices") to Icons.Default.Work,
    listOf("checks", "coupons") to Icons.Default.ConfirmationNumber,
    listOf("refunds") to Icons.Default.Replay,
    listOf("sale") to Icons.Default.Sell,
    listOf("sweets") to Icons.Default.Icecream,
)

private val GROUP_ICONS: Map<String, ImageVector> = mapOf(
    "food_and_drinks" to Icons.Default.Restaurant,
    "shopping" to Icons.Default.ShoppingBag,
    "housing" to Icons.Default.Home,
    "transportation" to Icons.Default.DirectionsBus,
    "vehicle" to Icons.Default.DirectionsCar,
    "life_and_entertainment" to Icons.Default.Person,
    "communication_pc" to Icons.Default.DesktopWindows,
    "financial_expenses" to Icons.Default.Payments,
    "investments" to Icons.Default.Hub,
    "income" to Icons.Default.AttachMoney,
    "others" to Icons.Default.MoreHoriz,
).let { slugs ->
    // Before the next catalog refresh a group is only known by its name, e.g. "Food & Drinks".
    slugs + slugs.mapKeys { (slug, _) -> slug.replace("_and_", " & ").replace("_pc", ", pc").replace('_', ' ') }
}
