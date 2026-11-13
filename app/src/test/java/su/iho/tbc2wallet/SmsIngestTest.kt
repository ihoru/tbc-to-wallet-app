package su.iho.tbc2wallet

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.TxSource
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.ingest.Ingest

/** Push and SMS for the same money make one transaction; codes make none. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmsIngestTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    /** Just after the sample SMS time (11:24:26 in Tbilisi), as on the phone. */
    private val now = java.time.Instant.parse("2026-11-12T07:25:00Z").toEpochMilli()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // One database instance lives across tests; Room refuses the main thread.
        java.util.concurrent.Executors.newSingleThreadExecutor().submit { AppDatabase.get(app).clearAllTables() }.get()
    }

    private fun rows() = runBlocking { AppDatabase.get(app).dao().observeTransactions().first() }

    @Test fun pushThenSmsIsOneTransaction() = runBlocking {
        Ingest.handle(app, "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026", "push-1", now, now)
        Ingest.handle(app, "Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: x", "sms:TBC SMS", now, now,
            source = TxSource.SMS)
        val rows = rows()
        assertEquals(1, rows.size)
        assertEquals(TxSource.PUSH, rows[0].source)
        assertEquals(TxSource.SMS, rows[0].alsoVia)
    }

    @Test fun repostedDuplicateStaysDropped() = runBlocking {
        val sms = "Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: x"
        val push = "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026"
        Ingest.handle(app, sms, "sms:TBC SMS", now, now, source = TxSource.SMS)
        Ingest.handle(app, push, "push-1", now, now)
        Ingest.handle(app, push, "push-1", now, now)   // Android re-posts the same push
        assertEquals(1, rows().size)
    }

    @Test fun sendingKeepsThePairing() = runBlocking {
        Ingest.handle(app, "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026", "push-1", now, now)
        val dao = AppDatabase.get(app).dao()
        val stale = rows().single()   // read by the sync worker before the SMS lands
        Ingest.handle(app, "Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: x", "sms:TBC SMS", now, now,
            source = TxSource.SMS)
        dao.upsertTx(stale.copy(status = TxStatus.SENT, walletRecordId = "r1"))
        assertEquals(TxSource.SMS, rows().single().alsoVia)
    }

    @Test fun identicalPurchaseBothWaysIsPairedThenANewOneStays() = runBlocking {
        val buy = "5.00 GEL\nTBC CARD VISA GOLD (***1234)\nCoffee Lab 12/11/26 11:20\nBalance: 100.00 GEL"
        Ingest.handle(app, buy, "push-1", now, now)
        Ingest.handle(app, buy, "sms:TBC SMS", now, now, source = TxSource.SMS)
        assertEquals(TxSource.SMS, rows().single().alsoVia)
        val next = "5.00 GEL\nTBC CARD VISA GOLD (***1234)\nCoffee Lab 12/11/26 11:22\nBalance: 95.00 GEL"
        Ingest.handle(app, next, "sms:TBC SMS", now + 120_000, now + 120_000, source = TxSource.SMS)
        assertEquals(2, rows().size)
    }

    @Test fun twoEqualSmsAreTwoTransactions() = runBlocking {
        val text = "Cash Deposit: 500.00 USD\nTBC CARD\n12/11/2026"
        Ingest.handle(app, text, "sms:TBC SMS", now, now, source = TxSource.SMS)
        Ingest.handle(app, text, "sms:TBC SMS", now + 60_000, now + 60_000, source = TxSource.SMS)
        assertEquals(2, rows().size)
    }

    @Test fun codesAreNotStored() = runBlocking {
        Ingest.handle(app, "Code: 288677\n401.00 GEL payment ***5190 at Employment.moh.gov.ge", "sms:TBC SMS", now, now, source = TxSource.SMS)
        Ingest.handle(app, "Sms Code: 1023\nPlease check that you enter this code only on tbconline or in mobilebank", "sms:TBC SMS", now, now,
            source = TxSource.SMS)
        assertEquals(0, rows().size)
    }

    /** An unreadable row as an older app version saved it. */
    private fun unparsed(text: String, source: String = TxSource.SMS, key: String = "old-unparsed", at: Long = now) = runBlocking {
        val tx = su.iho.tbc2wallet.data.TransactionEntity(
            dedupKey = key, kind = null, amount = null, currency = null, counterParty = null,
            accountLabel = null, cardLast4 = null, recordDateUtc = null,
            rawText = text, status = TxStatus.UNPARSED, receivedAt = at, source = source,
        )
        AppDatabase.get(app).dao().insertIfNew(tx)
        tx
    }

    private val boltSms = "12.50GEL\n(*5190)\nBoltTaxi\nBalance: 6335.35GEL\nYou have received: 0.04GEL\n" +
        "In Ertguli Piggy bank you have: 0.15GEL\n13/11/26 02:17"

    @Test fun reparseReadsAnOldUnreadableSms() = runBlocking {
        val old = unparsed(boltSms)
        assertEquals(Ingest.Reparse.PARSED, Ingest.reparse(app, old))
        val row = rows().single()
        assertEquals(TxStatus.PENDING, row.status)
        assertEquals("BoltTaxi", row.counterParty)
        assertEquals("12.50", row.amount)
        assertEquals(now, row.receivedAt)   // keeps its place in the list
        assertEquals(TxSource.SMS, row.source)
    }

    @Test fun reparseDropsItWhenThePushCopyIsAlreadyThere() = runBlocking {
        Ingest.handle(app, "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026", "push-1", now, now)
        // Arrived together with the push (handle stamps the real clock), so they are twins.
        val old = unparsed("Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: x", at = System.currentTimeMillis())
        assertEquals(Ingest.Reparse.DUPLICATE, Ingest.reparse(app, old))
        val row = rows().single()
        assertEquals(TxSource.PUSH, row.source)
        assertEquals(TxSource.SMS, row.alsoVia)
    }

    @Test fun reparseKeepsWhatStillDoesNotParse() = runBlocking {
        val old = unparsed("Something new from TBC")
        assertEquals(Ingest.Reparse.STILL_UNREADABLE, Ingest.reparse(app, old))
        assertEquals(TxStatus.UNPARSED, rows().single().status)
    }

    @Test fun reparseIgnoresCodes() = runBlocking {
        val old = unparsed("Sms Code: 1023\nPlease check that you enter this code only on tbconline or in mobilebank")
        assertEquals(Ingest.Reparse.IGNORED, Ingest.reparse(app, old))
        assertEquals(TxStatus.IGNORED, rows().single().status)
    }

    /** An older version saved it unreadable; after the update Android re-posts the same push. */
    @Test fun repostOfAnOldUnreadableIsReadOnce() = runBlocking {
        val dao = AppDatabase.get(app).dao()
        val text = "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026"
        // What the older version stored for it (same key formula as Ingest).
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("UNPARSED|$text|push-1|$now".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        unparsed(text, source = TxSource.PUSH, key = key)
        Ingest.handle(app, text, "push-1", now, now)   // re-post before Retry: read in place of the old row
        assertEquals(TxStatus.PENDING, rows().single().status)
        Ingest.handle(app, text, "push-1", now, now)   // and again: still one
        assertEquals(1, rows().size)
    }

    @Test fun repostAfterRetryIsNotSentTwice() = runBlocking {
        val text = "Deposit Money: 60.00 GEL\nTBC CARD\n12/11/2026"
        val key = java.security.MessageDigest.getInstance("SHA-256").digest("UNPARSED|$text|push-1|$now".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val old = unparsed(text, source = TxSource.PUSH, key = key)
        assertEquals(Ingest.Reparse.PARSED, Ingest.reparse(app, old))
        Ingest.handle(app, text, "push-1", now, now)
        assertEquals(1, rows().size)
    }
}
