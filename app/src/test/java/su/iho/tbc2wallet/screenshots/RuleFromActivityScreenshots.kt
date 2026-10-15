package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus

/** The rule dialog opened from an Activity card that's already in Wallet, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class RuleFromActivityScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        // Any stored token opens the app on Activity; it's never decrypted here.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        val db = AppDatabase.get(app)
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        dao.replaceCategories(listOf(CategoryEntity("groceries", "Groceries", "Food & Drinks")))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t1", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T19:28:00Z",
            rawText = "", status = TxStatus.SENT, walletRecordId = "r1", categorySource = "NONE",
            receivedAt = System.currentTimeMillis(),
        ))
        Unit
    }

    @Test fun ruleFromSentTransaction() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Shopsy"), 5_000)
        // Opening a dialog over the whole app never reports idle under Robolectric, so drive the clock by hand.
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Rule…")[0].performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/rule-from-activity.png")
    }
}
