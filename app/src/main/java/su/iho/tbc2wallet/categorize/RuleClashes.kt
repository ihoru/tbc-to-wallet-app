package su.iho.tbc2wallet.categorize

/**
 * Two of your rules clash when one payee can match both, for the same kind of transaction,
 * but they set a different category or different labels. Only one of them is applied
 * ([winner], per [CategoryResolver.RULE_PRIORITY]), which is easy to miss.
 */
data class RuleClash<T>(val first: T, val second: T, val winner: T)

object RuleClashes {
    /** [rules] must be in list order (oldest first), the same order the resolver breaks ties by. */
    fun <T> find(rules: List<T>, view: (T) -> UserRule): List<RuleClash<T>> {
        val clashes = mutableListOf<RuleClash<T>>()
        for (i in rules.indices) for (j in i + 1 until rules.size) {
            val a = view(rules[i]); val b = view(rules[j])
            if (!scopesOverlap(a, b) || !patternsOverlap(a, b) || sameOutcome(a, b)) continue
            val winner = if (CategoryResolver.RULE_PRIORITY.compare(a, b) <= 0) rules[i] else rules[j]
            clashes += RuleClash(rules[i], rules[j], winner)
        }
        return clashes
    }

    private fun scopesOverlap(a: UserRule, b: UserRule) =
        a.appliesTo == AppliesTo.ANY || b.appliesTo == AppliesTo.ANY || a.appliesTo == b.appliesTo

    private fun sameOutcome(a: UserRule, b: UserRule) =
        a.categoryId == b.categoryId && a.labelIds.toSet() == b.labelIds.toSet()

    /**
     * Whether some payee obviously matches both. Two "contains" rules only count when one
     * pattern contains the other; any two words could appear in one payee, but flagging every
     * pair would bury the real clashes.
     */
    private fun patternsOverlap(a: UserRule, b: UserRule): Boolean {
        val pa = a.pattern.trim(); val pb = b.pattern.trim()
        val ka = CategoryResolver.payeeKey(pa); val kb = CategoryResolver.payeeKey(pb)
        return when {
            a.matchType == MatchType.EXACT && b.matchType == MatchType.EXACT -> ka == kb
            a.matchType == MatchType.EXACT -> exactCanContain(pa, ka, pb, kb)
            b.matchType == MatchType.EXACT -> exactCanContain(pb, kb, pa, ka)
            else -> pa.contains(pb, ignoreCase = true) || pb.contains(pa, ignoreCase = true)
        }
    }

    /**
     * An exact rule matches any payee with the same normalized key ("Wolt-Market 12" for
     * "WOLT MARKET"), so a contains pattern can hit it through either the literal text or the key.
     */
    private fun exactCanContain(exact: String, exactKey: String, contains: String, containsKey: String) =
        exact.contains(contains, ignoreCase = true) || (containsKey.isNotEmpty() && exactKey.contains(containsKey))
}
