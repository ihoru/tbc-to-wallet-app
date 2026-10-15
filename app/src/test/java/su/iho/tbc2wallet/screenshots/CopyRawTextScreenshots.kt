package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
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

/** The copy button on Activity cards, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class CopyRawTextScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"
    private val shopsyRaw = "42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy 29/09/26 23:28\nBalance: 1500.00 GEL"

    @Before fun seed() = runBlocking {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t1", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T19:28:00Z",
            rawText = shopsyRaw, status = TxStatus.SENT, walletRecordId = "r1", categoryId = "groceries",
            categorySource = "USER_RULE", categoryName = "Food & Drinks › Groceries", receivedAt = now,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t2", kind = "EXPENSE", amount = "12.00", currency = "GEL", counterParty = "CITY GYM",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T18:15:00Z",
            rawText = "12.00 GEL\nTBC CARD VISA GOLD (***1234)\nCITY GYM 29/09/26 22:15\nBalance: 1542.10 GEL",
            status = TxStatus.FAILED, error = "Wallet answered 503", receivedAt = now - 1,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t3", kind = null, amount = null, currency = null, counterParty = null,
            accountLabel = null, cardLast4 = null, recordDateUtc = null,
            rawText = "Some new TBC message format\nwe can't read yet", status = TxStatus.UNPARSED, receivedAt = now - 2,
        ))
        Unit
    }

    @Test fun activityWithCopyButtons() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Shopsy"), 5_000)
        captureScreenRoboImage("$out/copy-raw-text.png")
        compose.onAllNodesWithContentDescription("Copy notification text")[0].performClick()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertEquals(shopsyRaw, clip.getItemAt(0).text.toString())
    }
}
