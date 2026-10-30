package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.iho.tbc2wallet.App
import su.iho.tbc2wallet.MainViewModel
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.sync.SyncWorker

/** Currency conversions on the Activity tab, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ConversionScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        dao.insertIfNew(TransactionEntity(
            dedupKey = "c1", kind = "CONVERSION", amount = "40.00", currency = "USD", counterParty = null,
            accountLabel = "Rate: 2.609", cardLast4 = null, recordDateUtc = "2026-10-30T04:50:00Z",
            rawText = "Conversion:\n104.36 GEL\n40.00 USD\nRate: 2.609\n30/10/2026", status = TxStatus.SENT,
            walletRecordId = "r1", categorySource = SyncWorker.TRANSFER_SOURCE, categoryName = "TBC GEL",
            receivedAt = now, counterAmount = "104.36", counterCurrency = "GEL",
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "c2", kind = "CONVERSION", amount = "100.00", currency = "EUR", counterParty = null,
            accountLabel = "Rate: 3.112", cardLast4 = null, recordDateUtc = "2026-10-29T09:12:00Z",
            rawText = "Conversion:\n311.20 GEL\n100.00 EUR\nRate: 3.112\n29/10/2026", status = TxStatus.NEEDS_ACCOUNT,
            receivedAt = now - 1, counterAmount = "311.20", counterCurrency = "GEL",
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t1", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-10-28T19:28:00Z",
            rawText = "42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy 28/10/26 23:28\nBalance: 1500.00 GEL",
            status = TxStatus.SENT, walletRecordId = "r2", categoryId = "groceries",
            categorySource = "USER_RULE", categoryName = "Food & Drinks › Groceries", receivedAt = now - 2,
        ))
        Unit
    }

    @Test fun activityWithConversions() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Shopsy"), 5_000)
        captureScreenRoboImage("$out/conversion-activity.png")
    }
}
