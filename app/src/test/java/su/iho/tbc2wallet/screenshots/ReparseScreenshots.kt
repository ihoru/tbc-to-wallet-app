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
import androidx.work.testing.WorkManagerTestInitHelper
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
import su.iho.tbc2wallet.data.TxSource
import su.iho.tbc2wallet.data.TxStatus

/** Retry on unreadable Activity cards, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ReparseScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        thread { db.clearAllTables() }.join()
        val now = System.currentTimeMillis()
        fun unreadable(key: String, text: String, at: Long) = TransactionEntity(
            dedupKey = key, kind = null, amount = null, currency = null, counterParty = null,
            accountLabel = null, cardLast4 = null, recordDateUtc = null,
            rawText = text, status = TxStatus.UNPARSED, receivedAt = at, source = TxSource.SMS,
        )
        db.dao().insertIfNew(unreadable("u1",
            "12.50GEL\n(*5190)\nBoltTaxi\nBalance: 6335.35GEL\nYou have received: 0.04GEL\n" +
                "In Ertguli Piggy bank you have: 0.15GEL\n13/11/26 02:17", now))
        db.dao().insertIfNew(unreadable("u2", "Some new TBC message format\nwe can't read yet", now - 60_000))
        Unit
    }

    @Test fun retryUnreadable() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("we can't read yet", substring = true), 5_000)
        captureScreenRoboImage("$out/reparse-1-before.png")
        compose.onAllNodesWithText("Retry")[0].performClick()
        compose.waitUntilAtLeastOneExists(hasText("BoltTaxi"), 5_000)
        compose.waitUntilAtLeastOneExists(hasText("Read it now", substring = true), 5_000)
        captureScreenRoboImage("$out/reparse-2-parsed.png")
    }

    @Test fun retryStillUnreadable() {
        thread { db.dao().let { runBlocking { it.setStatus("u1", TxStatus.DELETED) } } }.join()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("we can't read yet", substring = true), 5_000)
        compose.onAllNodesWithText("Retry")[0].performClick()
        compose.waitUntilAtLeastOneExists(hasText("Still can't read this one."), 5_000)
        captureScreenRoboImage("$out/reparse-3-still-unreadable.png")
    }
}
