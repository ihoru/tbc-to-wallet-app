package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

/** Swipe a transaction card to delete it, with its confirmation, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class SwipeDeleteScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        // Any stored token opens the app on Activity; it's never decrypted here.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        // The database instance outlives a single test, so start each one empty (not allowed on the main thread).
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        val now = System.currentTimeMillis()
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t1", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T19:28:00Z",
            rawText = "", status = TxStatus.SENT, walletRecordId = "r1", categoryId = "groceries",
            categorySource = "USER_RULE", categoryName = "Food & Drinks › Groceries", receivedAt = now,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t2", kind = "EXPENSE", amount = "12.50", currency = "GEL", counterParty = "Bolt",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T08:10:00Z",
            rawText = "", status = TxStatus.PENDING, receivedAt = now - 1,
        ))
        Unit
    }

    private fun showApp() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Shopsy"), 5_000)
    }

    @Test fun midSwipe() {
        showApp()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Shopsy").performTouchInput { down(center); moveBy(Offset(-80f, 0f)); moveBy(Offset(-200f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        captureScreenRoboImage("$out/1-swiping.png")
    }

    @Test fun confirmSent() = swipeAndConfirm("Shopsy", "2-confirm-in-wallet.png")

    @Test fun confirmQueued() = swipeAndConfirm("Bolt", "3-confirm-queued.png")

    @Test fun deleteRemovesRow() {
        showApp()
        compose.onNodeWithText("Shopsy").performTouchInput { swipeLeft() }
        compose.waitUntilAtLeastOneExists(hasText("Delete from this list?"), 5_000)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Shopsy")).fetchSemanticsNodes().isEmpty() }
        captureScreenRoboImage("$out/4-after-delete.png")
        // The row stays as a tombstone, and a send that was already under way can't bring it back.
        runBlocking {
            val dao = db.dao()
            assertEquals(TxStatus.DELETED, dao.statusOf("t1"))
            val pending = dao.toSend().single()
            dao.setStatus("t2", TxStatus.DELETED)
            dao.upsertTx(pending.copy(status = TxStatus.SENT, walletRecordId = "r2"))
            assertEquals(TxStatus.DELETED, dao.statusOf("t2"))
        }
    }

    private fun swipeAndConfirm(row: String, file: String) {
        showApp()
        compose.onNodeWithText(row).performTouchInput { swipeLeft() }
        compose.waitUntilAtLeastOneExists(hasText("Delete from this list?"), 5_000)
        captureScreenRoboImage("$out/$file")
    }
}
