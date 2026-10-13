package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import su.iho.tbc2wallet.data.AccountEntity
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.sync.SyncWorker

/** Transfer rules on the Activity and Rules tabs and in the rule dialog, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class TransferRuleScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        // Any stored token opens the app on Activity; it's never decrypted here.
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        val db = AppDatabase.get(app)
        // The database instance outlives a single test, so start each one empty (not allowed on the main thread).
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        dao.replaceAccounts(listOf(
            AccountEntity("acc-gel", "TBC card", "GEL", false),
            AccountEntity("acc-usd", "Cash USD", "USD", false),
            AccountEntity("acc-eur", "Revolut", "EUR", false),
        ))
        dao.replaceCategories(listOf(CategoryEntity("groceries", "Groceries", "Food & Drinks")))
        dao.insertUserRule(UserRuleEntity(
            pattern = "Shopsy", matchType = "CONTAINS", appliesTo = "EXPENSE",
            categoryId = "groceries", categoryName = "Food & Drinks › Groceries",
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
            rawText = "", status = TxStatus.SENT, walletRecordId = "r2", categoryId = "groceries",
            categorySource = "USER_RULE", categoryName = "Food & Drinks › Groceries", receivedAt = now - 1,
        ))
        Unit
    }

    private fun showApp() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Transfer from Revolut", substring = true), 5_000)
    }

    private fun showRules() {
        showApp()
        compose.onNodeWithText("Rules").performClick()
        compose.waitUntilAtLeastOneExists(hasText("→ Transfer with Revolut"), 5_000)
    }

    @Test fun activityTab() {
        showApp()
        captureScreenRoboImage("$out/activity-transfer.png")
    }

    @Test fun rulesTab() {
        showRules()
        captureScreenRoboImage("$out/rules-transfer.png")
    }

    @Test fun transferRuleDialog() = openRule("Payee is “John Smith” · income", "rule-dialog-transfer.png")

    @Test fun categoryRuleDialog() = openRule("Payee contains “Shopsy” · expenses", "rule-dialog-category.png")

    private fun openRule(row: String, file: String) {
        showRules()
        // With the whole app on screen, opening the dialog never reports idle under Robolectric,
        // so drive the clock by hand from here.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(row).performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/$file")
    }
}
