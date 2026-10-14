package su.iho.tbc2wallet

import kotlinx.serialization.json.JsonPrimitive
import su.iho.tbc2wallet.api.WalletApi
import su.iho.tbc2wallet.categorize.AppliesTo
import su.iho.tbc2wallet.categorize.CategoryResolver
import su.iho.tbc2wallet.categorize.CategorySource
import su.iho.tbc2wallet.categorize.CategoryTree
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WineBar
import su.iho.tbc2wallet.categorize.MatchType
import su.iho.tbc2wallet.categorize.PayeeLearner
import su.iho.tbc2wallet.categorize.RuleClashes
import su.iho.tbc2wallet.categorize.UserRule
import su.iho.tbc2wallet.categorize.WalletRule
import su.iho.tbc2wallet.data.RulesBackup
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.parse.TbcNotificationParser
import su.iho.tbc2wallet.parse.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class ParserTest {
    @Test fun purchase() {
        val t = TbcNotificationParser.parse(
            "42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy 29/09/26 23:28\nBalance: 1500.00 GEL"
        )!!
        assertEquals(TxKind.EXPENSE, t.kind)
        assertEquals(BigDecimal("42.10"), t.amount)
        assertEquals("GEL", t.currency)
        assertEquals("Shopsy", t.counterParty)
        assertEquals("1234", t.cardLast4)
        assertEquals(LocalDate.of(2026, 9, 29), t.date)
        assertEquals(LocalTime.of(23, 28), t.time)
        assertEquals(BigDecimal("1500.00"), t.balanceAfter)
    }

    @Test fun purchaseWithCashbackLines() {
        val t = TbcNotificationParser.parse(
            "12.00 GEL\nTBC CARD VISA GOLD (***1234)\nCITY GYM 29/09/26 22:15\nBalance: 1542.10 GEL\n" +
                "You’ve received: 0.05 GEL\nIn Ertguli Piggy bank you have: 25.30 GEL"
        )!!
        assertEquals(BigDecimal("12.00"), t.amount)
        assertEquals("CITY GYM", t.counterParty)
        assertEquals(BigDecimal("1542.10"), t.balanceAfter)
    }

    @Test fun foreignCurrencyWithThousands() {
        val t = TbcNotificationParser.parse(
            "1,250.00 USD\nTBC CARD VISA GOLD (***1234)\nAMAZON.COM*AB12 29/09/26 10:05\nBalance: 99.00 USD"
        )!!
        assertEquals(BigDecimal("1250.00"), t.amount)
        assertEquals("USD", t.currency)
    }

    @Test fun depositWithoutSender() {
        val t = TbcNotificationParser.parse("Deposit Money: 50.00 GEL\nTBC CARD\n29/09/2026")!!
        assertEquals(TxKind.INCOME, t.kind)
        assertEquals(BigDecimal("50.00"), t.amount)
        assertNull(t.counterParty)
        assertNull(t.time)
        assertEquals("TBC CARD", t.accountLabel)
    }

    @Test fun depositWithSender() {
        val t = TbcNotificationParser.parse("Deposit Money: 20.00 GEL\nTBC CARD\n29/09/2026\nJohn Smith")!!
        assertEquals("John Smith", t.counterParty)
    }

    @Test fun cashbackOnlyIsNotATransaction() {
        assertNull(TbcNotificationParser.parse("You’ve received: 0.05 GEL\nIn Ertguli Piggy bank you have: 25.35 GEL"))
    }
}

class ResolverTest {
    private val wallet = listOf(WalletRule(listOf("shopsy"), "cat-shopping", "Shopping", null))
    private val learned = PayeeLearner.learn(
        history = listOf(
            PayeeLearner.HistoryRecord("1", "CITY GYM", "cat-sport", "Sport"),
            PayeeLearner.HistoryRecord("2", "City Gym", "cat-sport", "Sport"),
            PayeeLearner.HistoryRecord("3", "CITY GYM", "cat-health", "Health"),
            PayeeLearner.HistoryRecord("4", "Shopsy", "cat-gifts", "Gifts"),
            PayeeLearner.HistoryRecord("5", "Shopsy", "cat-gifts", "Gifts"),
            PayeeLearner.HistoryRecord("6", "GLOVO 123", "cat-unknown", "Unknown"),
        ),
        unknownCategoryIds = setOf("cat-unknown"),
        assignedByApp = emptyMap(),
    )

    @Test fun userRuleBeatsEverything() {
        val r = CategoryResolver(
            listOf(UserRule("shopsy", MatchType.CONTAINS, AppliesTo.EXPENSE, "cat-gifts", "Gifts")), wallet, learned,
        )
        assertEquals(CategorySource.USER_RULE, r.resolve(TxKind.EXPENSE, "Shopsy", null).source)
    }

    @Test fun userRuleCarriesItsLabels() {
        val r = CategoryResolver(
            listOf(UserRule("shopsy", MatchType.CONTAINS, AppliesTo.ANY, "cat-gifts", "Gifts", listOf("lbl-1", "lbl-2"))),
            wallet, learned,
        )
        assertEquals(listOf("lbl-1", "lbl-2"), r.resolve(TxKind.EXPENSE, "Shopsy", null).labelIds)
        assertEquals(emptyList<String>(), r.resolve(TxKind.EXPENSE, "CITY GYM", null).labelIds)
    }

    @Test fun transferRuleSendsNoCategory() {
        val r = CategoryResolver(
            listOf(UserRule("john smith", MatchType.EXACT, AppliesTo.INCOME, "", "", transferAccountId = "acc-usd", transferAccountName = "Cash USD")),
            wallet, learned,
        )
        val d = r.resolve(TxKind.INCOME, "John Smith", null)
        assertEquals("acc-usd", d.transferAccountId)
        assertEquals("Cash USD", d.transferAccountName)
        assertNull(d.categoryId)
        assertEquals(CategorySource.USER_RULE, d.source)
        assertNull(r.resolve(TxKind.EXPENSE, "John Smith", null).transferAccountId) // income-only rule
    }

    private fun rule(p: String, m: MatchType = MatchType.CONTAINS, s: AppliesTo = AppliesTo.ANY, cat: String = "c1", labels: List<String> = emptyList()) =
        UserRule(p, m, s, cat, cat, labels)

    @Test fun nestedContainsRulesWithDifferentCategoriesClash() {
        val rules = listOf(rule("wolt"), rule("Wolt Market", cat = "c2"))
        val clashes = RuleClashes.find(rules) { it }
        assertEquals(1, clashes.size)
        assertEquals("Wolt Market", clashes[0].winner.pattern) // longer pattern is tried first
    }

    @Test fun exactRuleWinsOverContains() {
        val clashes = RuleClashes.find(listOf(rule("wolt", cat = "c2"), rule("Wolt Market", MatchType.EXACT))) { it }
        assertEquals(MatchType.EXACT, clashes.single().winner.matchType)
    }

    @Test fun exactRuleClashesThroughItsNormalizedKey() {
        // A payee "WOLT MARKET" matches both: exact by key, and it contains "wolt market".
        assertEquals(1, RuleClashes.find(listOf(rule("Wolt-Market", MatchType.EXACT), rule("wolt market", cat = "c2"))) { it }.size)
    }

    @Test fun differentLabelsAlsoClash() {
        assertEquals(1, RuleClashes.find(listOf(rule("wolt"), rule("wolt", labels = listOf("l1")))) { it }.size)
    }

    @Test fun noClashForSameOutcomeSeparateScopesOrUnrelatedPatterns() {
        assertEquals(0, RuleClashes.find(listOf(rule("wolt"), rule("wolt market"))) { it }.size)
        assertEquals(0, RuleClashes.find(listOf(rule("wolt", s = AppliesTo.EXPENSE), rule("wolt", s = AppliesTo.INCOME, cat = "c2"))) { it }.size)
        assertEquals(0, RuleClashes.find(listOf(rule("wolt"), rule("glovo", cat = "c2"))) { it }.size)
        assertEquals(0, RuleClashes.find(listOf(rule("wolt", MatchType.EXACT), rule("wolt market", MatchType.EXACT, cat = "c2"))) { it }.size)
    }

    @Test fun differentTransferAccountsClash() {
        val toCash = rule("wolt", cat = "").copy(transferAccountId = "acc-cash")
        assertEquals(1, RuleClashes.find(listOf(toCash, toCash.copy(transferAccountId = "acc-card"))) { it }.size)
        assertEquals(0, RuleClashes.find(listOf(toCash, toCash.copy(pattern = "WOLT"))) { it }.size)
    }

    @Test fun equalPriorityTieGoesToOlderRule() {
        val clashes = RuleClashes.find(listOf(rule("wolt"), rule("WOLT", cat = "c2"))) { it }
        assertEquals("c1", clashes.single().winner.categoryId)
    }

    @Test fun walletRuleBeatsLearned() {
        val d = CategoryResolver(emptyList(), wallet, learned).resolve(TxKind.EXPENSE, "Shopsy", null)
        assertEquals("cat-shopping", d.categoryId)
        assertEquals(CategorySource.WALLET_RULE, d.source)
    }

    @Test fun learnedIsFallback() {
        val d = CategoryResolver(emptyList(), wallet, learned).resolve(TxKind.EXPENSE, "CITY GYM", null)
        assertEquals("cat-sport", d.categoryId) // 2 of 3 votes
        assertEquals(CategorySource.LEARNED, d.source)
    }

    @Test fun unknownHistoryIsNotLearned() {
        val d = CategoryResolver(emptyList(), emptyList(), learned).resolve(TxKind.EXPENSE, "GLOVO 456", null)
        assertEquals(CategorySource.NONE, d.source)
    }

    @Test fun correctionOfAppGuessCountsTriple() {
        val map = PayeeLearner.learn(
            history = listOf(
                PayeeLearner.HistoryRecord("a", "Wolt", "cat-food", "Food"),
                PayeeLearner.HistoryRecord("b", "Wolt", "cat-food", "Food"),
                PayeeLearner.HistoryRecord("c", "Wolt", "cat-restaurant", "Restaurant"), // app guessed food, you fixed it
            ),
            unknownCategoryIds = emptySet(),
            assignedByApp = mapOf("c" to "cat-food"),
        )
        assertEquals("cat-restaurant", map.getValue("WOLT").categoryId)
    }
}

class RulesBackupTest {
    private val columns = listOf(
        RulesBackup.Column("id", "INTEGER", notNull = true, hasDefault = false),
        RulesBackup.Column("pattern", "TEXT", notNull = true, hasDefault = false),
        RulesBackup.Column("labelIds", "TEXT", notNull = true, hasDefault = false),
        RulesBackup.Column("transferAccountId", "TEXT", notNull = false, hasDefault = false),
    )

    @Test fun olderBackupGetsEmptyValuesForNewRequiredColumns() {
        val row = mapOf("id" to JsonPrimitive(3), "pattern" to JsonPrimitive("Wolt"))
        val fitted = RulesBackup.completeRow(columns, row)
        assertEquals(setOf("id", "pattern", "labelIds"), fitted.keys)
        assertEquals("", (fitted["labelIds"] as JsonPrimitive).content)
    }

    @Test fun newerBackupDropsUnknownColumns() {
        val row = mapOf(
            "id" to JsonPrimitive(1),
            "pattern" to JsonPrimitive("Bolt"),
            "labelIds" to JsonPrimitive("a\nb"),
            "fromTheFuture" to JsonPrimitive("x"),
        )
        val fitted = RulesBackup.completeRow(columns, row)
        assertEquals(setOf("id", "pattern", "labelIds"), fitted.keys)
    }
}

class WalletJsonTest {
    @Test fun transferRecordCarriesPairingMode() {
        val record = WalletApi.NewRecord(
            accountId = "acc-gel", amount = WalletApi.Amount(-20.0, "GEL"), recordDate = "2026-09-29T08:00:00Z",
            transfer = WalletApi.Transfer("acc-usd", pairingMode = "new"),
        )
        val json = WalletApi.json.encodeToString(WalletApi.NewRecord.serializer(), record)
        assertTrue(json, json.contains(""""transfer":{"accountId":"acc-usd","pairingMode":"new"}"""))
        assertTrue(json, !json.contains("categoryId"))
    }
}

class CategoryIconTest {
    @Test fun groupSlugsMatchWholeAndCategoriesByTheirOwnPart() {
        assertEquals(Icons.Default.DirectionsBus, categoryIcon("transportation")) // not "sport"
        assertEquals(Icons.Default.LocalTaxi, categoryIcon("transportation__taxi"))
        assertEquals(Icons.Default.Restaurant, categoryIcon("Food & Drinks"))
        assertEquals(Icons.Default.Restaurant, categoryIcon("food_and_drinks__something_new"))
        assertEquals(Icons.Default.WineBar, categoryIcon("food_and_drinks__bar_cafe"))
    }
}

class CategoryTreeTest {
    private val cats = listOf(
        CategoryEntity("3", "Restaurant", "Food & Drinks"),
        CategoryEntity("1", "Food & Drinks", null),
        CategoryEntity("2", "Groceries", "Food & Drinks"),
        CategoryEntity("4", "Taxi", "Transportation"),
        CategoryEntity("5", "Income", null),
    )

    @Test fun groupsChildrenUnderParentSorted() {
        val tree = CategoryTree.build(cats)
        assertEquals(listOf("Food & Drinks", "Income", "Transportation"), tree.map { it.name })
        assertEquals(listOf("Groceries", "Restaurant"), tree[0].children.map { it.name })
        assertEquals("1", tree[0].category?.id)
        assertNull(tree[2].category) // parent not in the list: heading only
    }

    @Test fun filterKeepsMatchingChildrenUnderParent() {
        val tree = CategoryTree.build(cats)
        val f = CategoryTree.filter(tree, "rest")
        assertEquals(listOf("Food & Drinks"), f.map { it.name })
        assertEquals(listOf("Restaurant"), f[0].children.map { it.name })
        assertEquals(2, CategoryTree.filter(tree, "food")[0].children.size)
        assertEquals(tree, CategoryTree.filter(tree, "  "))
        // editing a picked "Parent › Child" value keeps that child listed
        assertEquals(listOf("Restaurant"), CategoryTree.filter(tree, "Food & Drinks › Restauran")[0].children.map { it.name })
    }

    private val wallet = listOf(
        CategoryEntity("g", "Food & Drinks", null, groupId = "food_and_drinks", groupName = "Food & Drinks", color = "#f00", systemId = "food_and_drinks"),
        CategoryEntity("r", "Restaurant", null, groupId = "food_and_drinks", groupName = "Food & Drinks", systemId = "food_and_drinks__restaurant"),
        CategoryEntity("b", "Bar", null, groupId = "food_and_drinks", groupName = "Food & Drinks", systemId = "food_and_drinks__bar"),
        CategoryEntity("w", "Wolt", "Restaurant", parentId = "r", groupId = "food_and_drinks", groupName = "Food & Drinks"),
        CategoryEntity("i", "Wage", null, groupId = "income", groupName = "Income", color = "#0f0", systemId = "income__wage"),
        CategoryEntity("s", "Rent", null, groupId = "housing", groupName = "Housing", color = "#00f", systemId = "housing__rent"),
    )

    @Test fun walletGroupsCategoriesAndCustomSubcategories() {
        val tree = CategoryTree.build(wallet)
        // Wallet's group order, not alphabetical.
        assertEquals(listOf("Food & Drinks", "Housing", "Income"), tree.map { it.name })
        val food = tree[0]
        assertEquals("g", food.category?.id)
        assertEquals(listOf("Bar", "Restaurant"), food.children.map { it.name })
        assertEquals(listOf("Wolt"), food.children[1].children.map { it.name })
        assertEquals("#f00", food.children[1].children[0].color) // inherited from the group
        assertNull(tree[2].category) // Income has no category of its own here: heading only
    }

    @Test fun searchFindsDeepRowsAndEditedPaths() {
        val tree = CategoryTree.build(wallet)
        val f = CategoryTree.filter(tree, "wol")
        assertEquals(listOf("Food & Drinks"), f.map { it.name })
        assertEquals(listOf("Restaurant"), f[0].children.map { it.name })
        assertEquals(listOf("Wolt"), CategoryTree.filter(tree, "Restaurant › Wo")[0].children[0].children.map { it.name })
    }
}
