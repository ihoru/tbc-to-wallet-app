package su.iho.tbc2wallet.categorize

import su.iho.tbc2wallet.data.CategoryEntity

/**
 * One row of the category tree, laid out the way Wallet's own picker is:
 * group (Food & Drinks) › category (Groceries) › your custom subcategories.
 * [category] is null for a heading that isn't itself a category you can pick
 * (e.g. a group with no category of its own, or an archived parent).
 * [color] is Wallet's hex color: the group's for its categories, a custom subcategory's own
 * (or its parent's when it has none).
 */
data class CategoryNode(
    val key: String,
    val name: String,
    val category: CategoryEntity?,
    val color: String?,
    val children: List<CategoryNode> = emptyList(),
    /** Names from the top of the tree down to this row, joined with " › ". */
    val path: String = name,
    /** What picks the row's glyph: a system id, a group slug, or a name. */
    val iconKey: String = name,
)

object CategoryTree {
    /** Wallet's own order of its root groups; unknown groups follow, by name. */
    private val GROUP_ORDER = listOf(
        "food_and_drinks", "shopping", "housing", "transportation", "vehicle", "life_and_entertainment",
        "communication_pc", "financial_expenses", "investments", "income", "others",
    )

    private val byName = compareBy<CategoryEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.name }

    fun build(categories: List<CategoryEntity>): List<CategoryNode> {
        val customs = categories.filter { it.parentId != null || !it.parentName.isNullOrBlank() }
        val bases = categories - customs.toSet()
        val byId = categories.associateBy { it.id }
        val baseByName = bases.associateBy { it.name }
        // A custom subcategory hangs off its base category: by id when Wallet sends it, else by name.
        val customsByBase = customs.groupBy { c ->
            c.parentId?.takeIf { it in byId } ?: baseByName[c.parentName]?.id ?: "name:${c.parentName}"
        }

        // Custom subcategories take their group's glyph, as in Wallet.
        fun custom(c: CategoryEntity, color: String?, parentPath: String, icon: String) =
            CategoryNode(c.id, c.name, c, c.color ?: color, path = "$parentPath › ${c.name}", iconKey = icon)

        fun leafOrBase(c: CategoryEntity, color: String?, parentPath: String?, groupIcon: String?): CategoryNode {
            val path = parentPath?.let { "$it › ${c.name}" } ?: c.name
            val kids = customsByBase[c.id].orEmpty().sortedWith(byName)
                .map { custom(it, color, path, groupIcon ?: c.systemId ?: c.name) }
            return CategoryNode(c.id, c.name, c, color, kids, path, iconKey = c.systemId ?: c.name)
        }

        val roots = mutableListOf<CategoryNode>()
        val (grouped, ungrouped) = bases.partition { !it.groupId.isNullOrBlank() }
        grouped.groupBy { it.groupId!! }.forEach { (groupId, members) ->
            val groupName = members.firstNotNullOfOrNull { it.groupName } ?: groupId
            val own = ownCategory(groupId, groupName, members)
            // Wallet paints a group's categories in the group's color, whatever stray colors the API
            // reports for some of them (e.g. #cccccc), so take the color most of them share.
            val color = members.mapNotNull { it.color?.lowercase() }.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key == own?.color?.lowercase() })?.key
            val kids = (members - listOfNotNull(own).toSet()).sortedWith(byName)
                .map { leafOrBase(it, color, groupName, groupId) }
            val ownCustoms = own?.let { customsByBase[it.id] }.orEmpty().sortedWith(byName)
                .map { custom(it, color, groupName, groupId) }
            roots += CategoryNode("group:$groupId", groupName, own, color, ownCustoms + kids, iconKey = groupId)
        }
        ungrouped.forEach { roots += leafOrBase(it, it.color, null, null) }
        // Custom subcategories whose base isn't in the list (archived, say) get a heading of their own.
        customsByBase.filterKeys { it.startsWith("name:") }.forEach { (key, kids) ->
            val name = key.removePrefix("name:")
            roots += CategoryNode(key, name, null, kids.firstNotNullOfOrNull { it.color },
                kids.sortedWith(byName).map { CategoryNode(it.id, it.name, it, it.color, path = "$name › ${it.name}") })
        }

        val groupIndex = { n: CategoryNode ->
            GROUP_ORDER.indexOf(n.key.removePrefix("group:")).let { if (it < 0) GROUP_ORDER.size else it }
        }
        return roots.sortedWith(compareBy<CategoryNode> { groupIndex(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * The group's own category (Food & Drinks inside Food & Drinks), if Wallet has one: its system id
     * is the group's slug, or failing that its name is the group's.
     */
    private fun ownCategory(groupId: String, groupName: String, members: List<CategoryEntity>): CategoryEntity? {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return members.firstOrNull { it.systemId == groupId }
            ?: members.firstOrNull { it.systemId?.substringAfter("__") == groupId }
            ?: members.firstOrNull { norm(it.name) == norm(groupName) }
    }

    /**
     * Keeps a row whose name or path matches [query] with everything under it, and otherwise
     * only the rows below it that match. Matching on the path means editing a picked
     * "Parent › Child" value still finds that child.
     */
    fun filter(tree: List<CategoryNode>, query: String): List<CategoryNode> {
        val q = query.trim()
        if (q.isEmpty()) return tree
        return tree.mapNotNull { node ->
            if (node.path.contains(q, ignoreCase = true)) node
            else filter(node.children, q).takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
        }
    }
}
