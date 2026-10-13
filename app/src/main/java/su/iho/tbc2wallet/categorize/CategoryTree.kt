package su.iho.tbc2wallet.categorize

import su.iho.tbc2wallet.data.CategoryEntity

/**
 * A top-level Wallet category and its subcategories.
 * [category] is null when Wallet lists subcategories under a parent name that is not
 * itself in the category list (e.g. the parent is archived); the parent is then only a heading.
 */
data class CategoryNode(
    val name: String,
    val category: CategoryEntity?,
    val children: List<CategoryEntity>,
)

object CategoryTree {
    /** Groups categories by parent, both levels sorted by name. */
    fun build(categories: List<CategoryEntity>): List<CategoryNode> {
        val (subs, tops) = categories.partition { !it.parentName.isNullOrBlank() }
        val childrenByParent = subs.groupBy { it.parentName!! }
        val topByName = tops.associateBy { it.name }
        val parentNames = (topByName.keys + childrenByParent.keys).sortedWith(String.CASE_INSENSITIVE_ORDER)
        return parentNames.map { name ->
            CategoryNode(
                name = name,
                category = topByName[name],
                children = childrenByParent[name].orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            )
        }
    }

    /**
     * Keeps parents whose name matches [query] with all their children,
     * and otherwise only the children that match, under their parent.
     */
    fun filter(tree: List<CategoryNode>, query: String): List<CategoryNode> {
        val q = query.trim()
        if (q.isEmpty()) return tree
        return tree.mapNotNull { node ->
            if (node.name.contains(q, ignoreCase = true)) node
            else node.children.filter { it.name.contains(q, ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
                ?.let { node.copy(children = it) }
        }
    }
}
