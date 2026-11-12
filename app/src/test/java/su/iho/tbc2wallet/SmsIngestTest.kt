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
}
