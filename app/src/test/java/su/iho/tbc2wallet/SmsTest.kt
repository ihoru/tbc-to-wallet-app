package su.iho.tbc2wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxSource
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.ingest.TbcSmsReceiver
import su.iho.tbc2wallet.ingest.Twins
import su.iho.tbc2wallet.parse.TbcNotificationParser
import su.iho.tbc2wallet.parse.TxKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class SmsParserTest {
    @Test fun receivedAmount() {
        val t = TbcNotificationParser.parse(
            "Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: პირადი გადარიცხვა"
        )!!
        assertEquals(TxKind.INCOME, t.kind)
        assertEquals(BigDecimal("60.00"), t.amount)
        assertEquals("GEL", t.currency)
        assertEquals("GE61TB****0007", t.accountLabel)
        assertEquals("პირადი გადარიცხვა", t.counterParty)
        assertEquals(LocalDate.of(2026, 11, 12), t.date)
        assertEquals(LocalTime.of(11, 24, 26), t.time)
    }

    @Test fun receivedAmountWithoutDescription() {
        val t = TbcNotificationParser.parse("Received Amount: 30.00 GEL\nGE61TB****0007\n2026/11/11 20:13:26")!!
        assertNull(t.counterParty)
        assertEquals(LocalTime.of(20, 13, 26), t.time)
    }

    @Test fun cashDeposit() {
        val t = TbcNotificationParser.parse("Cash Deposit: 500.00 USD\nTBC CARD\n12/09/2026")!!
        assertEquals(TxKind.INCOME, t.kind)
        assertEquals(BigDecimal("500.00"), t.amount)
        assertEquals("USD", t.currency)
        assertEquals("TBC CARD", t.accountLabel)
        assertEquals("Cash Deposit", t.counterParty)
        assertEquals(LocalDate.of(2026, 9, 12), t.date)
        assertNull(t.time)
    }

    @Test fun conversionSmsReadsLikeThePush() {
        val t = TbcNotificationParser.parse("Conversion:\n2598.00 GEL\n1000.00 USD\nRate: 2.598\n12/11/2026")!!
        assertEquals(TxKind.CONVERSION, t.kind)
        assertEquals(BigDecimal("2598.00"), t.amount)
        assertEquals(BigDecimal("1000.00"), t.counterAmount)
        assertEquals("USD", t.counterCurrency)
    }

    @Test fun cardPurchaseSms() {
        val t = TbcNotificationParser.parse("72.03GEL\n(*5190)\nWILDBERRIES\nBalance: 7017.87GEL \n \n12/11/26 16:43")!!
        assertEquals(TxKind.EXPENSE, t.kind)
        assertEquals(BigDecimal("72.03"), t.amount)
        assertEquals("GEL", t.currency)
        assertEquals("WILDBERRIES", t.counterParty)
        assertEquals("5190", t.cardLast4)
        assertEquals(LocalDate.of(2026, 11, 12), t.date)
        assertEquals(LocalTime.of(16, 43), t.time)
        assertEquals(BigDecimal("7017.87"), t.balanceAfter)
    }

    @Test fun mobileBalanceRecharge() {
        val t = TbcNotificationParser.parse("Mobile Balance Recharge\n35.00GEL\nAccount refill (magti)\nID:591006815\n12/11/2026")!!
        assertEquals(TxKind.EXPENSE, t.kind)
        assertEquals(BigDecimal("35.00"), t.amount)
        assertEquals("GEL", t.currency)
        assertEquals("Account refill (magti)", t.counterParty)
        assertEquals("ID:591006815", t.accountLabel)
        assertEquals(LocalDate.of(2026, 11, 12), t.date)
        assertNull(t.time)
    }

    @Test fun codesAndAlertsAreNoise() {
        assertTrue(TbcNotificationParser.isNoise("Sms Code: 1023\nPlease check that you enter this code only on tbconline or in mobilebank"))
        assertTrue(TbcNotificationParser.isNoise("Code: 288677\n401.00 GEL payment ***5190 at Employment.moh.gov.ge"))
        assertTrue(TbcNotificationParser.isNoise("Code: 387509\n56.70 GEL payment ***5190 at Mywatershop"))
        assertTrue(TbcNotificationParser.isNoise(
            "A new device or browser just accessed your Digital Bank.\nWas that you? If not, we suggest you change your password right away."
        ))
        assertFalse(TbcNotificationParser.isNoise("Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26"))
        assertFalse(TbcNotificationParser.isNoise("42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy 29/09/26 23:28"))
    }

    @Test fun senderMatching() {
        assertTrue(TbcSmsReceiver.isTbcSender("TBC SMS"))
        assertTrue(TbcSmsReceiver.isTbcSender("tbc_sms"))
        assertFalse(TbcSmsReceiver.isTbcSender("TBC"))
        assertFalse(TbcSmsReceiver.isTbcSender("+995555123456"))
        assertFalse(TbcSmsReceiver.isTbcSender(null))
        assertTrue(TbcSmsReceiver.isTbcSender("TBC", listOf("TBC SMS", "tbc")))
        assertFalse(TbcSmsReceiver.isTbcSender("TBC SMS", emptyList()))
        assertFalse(TbcSmsReceiver.isTbcSender("", listOf("")))
    }
}

class TwinsTest {
    private val min = 60_000L
    private fun tx(key: String, source: String, at: Long, amount: String = "60.00", kind: String = "INCOME",
                   card: String? = null, alsoVia: String? = null, fake: Boolean = false,
                   counterAmount: String? = null, counterCurrency: String? = null) = TransactionEntity(
        dedupKey = key, kind = kind, amount = amount, currency = "GEL", counterParty = null, accountLabel = null,
        cardLast4 = card, recordDateUtc = null, rawText = "", status = TxStatus.SENT, receivedAt = at, isFake = fake,
        source = source, alsoVia = alsoVia, counterAmount = counterAmount, counterCurrency = counterCurrency,
    )

    @Test fun smsAfterPushIsItsTwin() =
        assertEquals("push", Twins.find(tx("sms", TxSource.SMS, 3 * min), listOf(tx("push", TxSource.PUSH, 0)))?.dedupKey)

    @Test fun sameSourceIsNeverATwin() =
        assertNull(Twins.find(tx("b", TxSource.PUSH, min), listOf(tx("a", TxSource.PUSH, 0))))

    @Test fun differentAmountKindCardOrFarApartIsNotATwin() {
        val sms = tx("sms", TxSource.SMS, 0, card = "5190", kind = "EXPENSE")
        assertNull(Twins.find(sms, listOf(tx("p", TxSource.PUSH, 0, amount = "61.00", kind = "EXPENSE"))))
        assertNull(Twins.find(sms, listOf(tx("p", TxSource.PUSH, 0, kind = "INCOME"))))
        assertNull(Twins.find(sms, listOf(tx("p", TxSource.PUSH, 0, card = "1234", kind = "EXPENSE"))))
        assertNull(Twins.find(sms, listOf(tx("p", TxSource.PUSH, 31 * min, kind = "EXPENSE"))))
        assertNull(Twins.find(sms, listOf(tx("p", TxSource.PUSH, 0, kind = "EXPENSE", fake = true))))
        assertEquals("p", Twins.find(sms, listOf(tx("p", TxSource.PUSH, 0, amount = "60", kind = "EXPENSE")))?.dedupKey)
    }

    @Test fun anotherMerchantOrTimeIsNotATwin() {
        val sms = tx("sms", TxSource.SMS, 0, kind = "EXPENSE").copy(counterParty = "Shopsy", recordDateUtc = "2026-11-12T08:00:00Z")
        val push = tx("p", TxSource.PUSH, 0, kind = "EXPENSE").copy(counterParty = "SHOPSY", recordDateUtc = "2026-11-12T08:00:00Z")
        assertEquals("p", Twins.find(sms, listOf(push))?.dedupKey)
        assertNull(Twins.find(sms, listOf(push.copy(counterParty = "Coffee Lab"))))
        assertNull(Twins.find(sms, listOf(push.copy(recordDateUtc = "2026-11-12T07:20:00Z"))))
        // Incoming money: the push names the sender, the SMS a description; they still pair.
        val income = tx("i", TxSource.SMS, 0).copy(counterParty = "personal transfer")
        assertEquals("p", Twins.find(income, listOf(tx("p", TxSource.PUSH, 0).copy(counterParty = "John Smith")))?.dedupKey)
    }

    @Test fun conversionNeedsTheSameOtherSide() {
        val sms = tx("sms", TxSource.SMS, 0, amount = "2598.00", kind = "CONVERSION", counterAmount = "1000.00", counterCurrency = "USD")
        assertNull(Twins.find(sms, listOf(sms.copy(dedupKey = "p", source = TxSource.PUSH, counterAmount = "999.00"))))
        assertEquals("p", Twins.find(sms, listOf(sms.copy(dedupKey = "p", source = TxSource.PUSH, counterAmount = "1000")))?.dedupKey)
    }

    /** Two equal 5 GEL coffees, each by push and by SMS, make two records, not one. */
    @Test fun eachRecordPairsOnce() {
        val push1 = tx("push1", TxSource.PUSH, 0, amount = "5.00", alsoVia = TxSource.SMS)   // already paired with sms1
        val push2 = tx("push2", TxSource.PUSH, 2 * min, amount = "5.00")
        assertEquals("push2", Twins.find(tx("sms2", TxSource.SMS, 3 * min, amount = "5.00"), listOf(push1, push2))?.dedupKey)
        assertNull(Twins.find(tx("sms3", TxSource.SMS, 4 * min, amount = "5.00"), listOf(push1)))
    }
}
