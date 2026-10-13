package su.iho.tbc2wallet

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import su.iho.tbc2wallet.data.AccountEntity
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.sync.SyncWorker

/**
 * Renders the real screens with sample data and saves PNGs to app/build/screenshots,
 * so UI changes can be shown without a phone. CI uploads them as the "screenshots" artifact.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun seed() = runBlocking {
        // Each test gets a fresh app, so drop the database bound to the previous one.
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        // Any stored token opens the app on Activity; it's never decrypted here.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("wallet_token_enc", "sample").commit()
        val dao = AppDatabase.get(app).dao()
        dao.replaceAccounts(listOf(
            AccountEntity("acc-gel", "TBC card", "GEL", false),
            AccountEntity("acc-usd", "Cash USD", "USD", false),
            AccountEntity("acc-eur", "Revolut", "EUR", false),
        ))
        dao.replaceCategories(listOf(
            CategoryEntity("cat-groceries", "Groceries", "Food & Drinks"),
            CategoryEntity("cat-gym", "Active sport, fitness", "Life & Entertainment"),
        ))
        dao.insertUserRule(UserRuleEntity(
            pattern = "Shopsy", matchType = "CONTAINS", appliesTo = "EXPENSE",
            categoryId = "cat-groceries", categoryName = "Food & Drinks › Groceries",
        ))
        dao.insertUserRule(UserRuleEntity(
            pattern = "John Smith", matchType = "EXACT", appliesTo = "INCOME", categoryId = "", categoryName = "",
            transferAccountId = "acc-eur", transferAccountName = "Revolut",
        ))
        val now = System.currentTimeMillis()
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t1", kind = "INCOME", amount = "200.00", currency = "GEL", counterParty = "John Smith",
            accountLabel = "TBC CARD", cardLast4 = null, recordDateUtc = "2026-09-29T08:00:00Z", rawText = "",
            status = TxStatus.SENT, walletRecordId = "r1", categorySource = SyncWorker.TRANSFER_SOURCE,
            categoryName = "Revolut", receivedAt = now,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "t2", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-09-29T19:28:00Z",
            rawText = "", status = TxStatus.SENT, walletRecordId = "r2", categoryId = "cat-groceries",
            categorySource = "USER_RULE", categoryName = "Food & Drinks › Groceries", receivedAt = now - 1,
        ))
        Unit
    }

    @Test fun activityAndRules() {
        start()
        shot("activity")
        compose.onNodeWithText("Rules").performClick()
        settle()
        shot("rules")
    }

    @Test fun categoryRuleDialog() = ruleDialog("Shopsy", "rule-dialog-category")

    @Test fun transferRuleDialog() = ruleDialog("John Smith", "rule-dialog-transfer")

    private fun ruleDialog(payee: String, name: String) {
        start()
        compose.onNodeWithText("Rules").performClick()
        settle()
        compose.onAllNodesWithText(payee, substring = true).onFirst().performClick()
        // Under Robolectric an open dialog never reports idle, so only run frames from here on.
        repeat(10) { compose.mainClock.advanceTimeBy(100); ShadowLooper.idleMainLooper() }
        shot(name)
    }

    private fun start() {
        val vm = MainViewModel(app)
        // Drive the clock by hand so nothing animated keeps Compose from ever being idle.
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(vm) } }
        settle()
    }

    private fun settle() {
        repeat(5) { compose.mainClock.advanceTimeBy(500); compose.waitForIdle() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/screenshots/$name.png")
}
