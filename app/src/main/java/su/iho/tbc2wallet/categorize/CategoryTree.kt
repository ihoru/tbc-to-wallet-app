package su.iho.tbc2wallet.categorize

import su.iho.tbc2wallet.data.CategoryEntity

/**
 * One row of the category tree, laid out the way Wallet's own picker is:
 * group (Food & Drinks) › category (Groceries) › your custom subcategories.
 * [category] is null for a heading that isn't itself a category you can pick
 * (e.g. a group with no category of its own, or an archived parent).
 * [color] is Wallet's hex color, inherited from the parent when a row has none.
 */
data class CategoryNode(
    val key: String,
    val name: String,
    val category: CategoryEntity?,
    val color: String?,
    val children: List<CategoryNode> = emptyList(),
    /** Names from the top of the tree down to this row, joined with " › ". */
    val path: String = name,
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

        fun leafOrBase(c: CategoryEntity, parentColor: String?, parentPath: String?): CategoryNode {
            val color = c.color ?: parentColor
            val path = parentPath?.let { "$it › ${c.name}" } ?: c.name
            val kids = customsByBase[c.id].orEmpty().sortedWith(byName)
                .map { CategoryNode(it.id, it.name, it, it.color ?: color, path = "$path › ${it.name}") }
            return CategoryNode(c.id, c.name, c, color, kids, path)
        }

        val roots = mutableListOf<CategoryNode>()
        val (grouped, ungrouped) = bases.partition { !it.groupId.isNullOrBlank() }
        grouped.groupBy { it.groupId!! }.forEach { (groupId, members) ->
            val groupName = members.firstNotNullOfOrNull { it.groupName } ?: groupId
            // The group's own category, if Wallet has one (its system id is the group's slug).
            val own = members.firstOrNull { it.systemId == groupId }
                ?: members.firstOrNull { it.systemId == null && it.name == groupName }
            val color = own?.color ?: members.firstNotNullOfOrNull { it.color }
            val kids = (members - listOfNotNull(own).toSet()).sortedWith(byName).map { leafOrBase(it, color, groupName) }
            val ownCustoms = own?.let { customsByBase[it.id] }.orEmpty().sortedWith(byName)
                .map { CategoryNode(it.id, it.name, it, it.color ?: color, path = "$groupName › ${it.name}") }
            roots += CategoryNode("group:$groupId", groupName, own, color, ownCustoms + kids)
        }
        ungrouped.forEach { roots += leafOrBase(it, null, null) }
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
