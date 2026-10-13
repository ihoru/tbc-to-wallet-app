package app.tbcwallet

import app.tbcwallet.categorize.AppliesTo
import app.tbcwallet.categorize.CategoryResolver
import app.tbcwallet.categorize.CategorySource
import app.tbcwallet.categorize.MatchType
import app.tbcwallet.categorize.PayeeLearner
import app.tbcwallet.categorize.UserRule
import app.tbcwallet.categorize.WalletRule
import app.tbcwallet.parse.TbcNotificationParser
import app.tbcwallet.parse.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
