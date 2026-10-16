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
import java.util.TimeZone

/** Reversal cards in Activity, seeded with the owner's FLT*Jetshr case, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ReversalScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"
    private val card = "TBC CARD VISA GOLD (***5190)"

    private fun charge(key: String, amount: String, at: String, received: Long, status: TxStatus = TxStatus.SENT) = TransactionEntity(
        dedupKey = key, kind = "EXPENSE", amount = amount, currency = "GEL", counterParty = "FLT*Jetshr",
        accountLabel = card, cardLast4 = "5190", recordDateUtc = at, rawText = "", status = status,
        walletRecordId = "r-$key", categorySource = "NONE", receivedAt = received,
    )

    private fun reversal(key: String, amount: String, at: String, received: Long, status: TxStatus, reverses: String?) = TransactionEntity(
        dedupKey = key, kind = "REVERSAL", amount = amount, currency = "GEL", counterParty = "FLT*Jetshr",
        accountLabel = card, cardLast4 = "5190", recordDateUtc = at, rawText = "", status = status,
        reversesKey = reverses, receivedAt = received,
    )

    private fun seed(vararg rows: TransactionEntity) = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tbilisi"))
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        thread { db.clearAllTables() }.join()
        rows.forEach { db.dao().insertIfNew(it) }
    }

    private fun shoot(file: String) {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("↩", substring = true), 5_000)
        compose.waitForIdle()
        captureScreenRoboImage("$out/$file")
    }

    @Test fun cancelling() {
        seed(
            charge("a", "1.90", "2026-10-15T20:48:00Z", 1), charge("b", "4.00", "2026-10-15T20:49:00Z", 2),
            charge("c", "3.20", "2026-10-15T20:56:00Z", 3),
            reversal("rev", "4.00", "2026-10-15T20:56:00Z", 4, TxStatus.REVERSING, "b"),
        )
        shoot("reversal-1-cancelling.png")
    }

    @Test fun cancelled() {
        seed(
            charge("a", "1.90", "2026-10-15T20:48:00Z", 1), charge("b", "4.00", "2026-10-15T20:49:00Z", 2, TxStatus.DELETED),
            charge("c", "3.20", "2026-10-15T20:56:00Z", 3),
            reversal("rev", "4.00", "2026-10-15T20:56:00Z", 4, TxStatus.REVERSED, "b"),
        )
        shoot("reversal-2-cancelled.png")
    }

    @Test fun noMatch() {
        seed(
            charge("a", "1.90", "2026-10-15T20:48:00Z", 1), charge("c", "3.20", "2026-10-15T20:56:00Z", 3),
            reversal("rev", "4.00", "2026-10-15T20:56:00Z", 4, TxStatus.NO_MATCH, null),
        )
        shoot("reversal-3-no-match.png")
    }
}
