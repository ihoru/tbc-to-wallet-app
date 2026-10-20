package su.iho.tbc2wallet.screenshots

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
import su.iho.tbc2wallet.BackfillScreen
import su.iho.tbc2wallet.MainViewModel
import su.iho.tbc2wallet.categorize.AppliesTo
import su.iho.tbc2wallet.categorize.MatchType
import su.iho.tbc2wallet.categorize.UserRule
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.UserRuleEntity
import su.iho.tbc2wallet.sync.RuleBackfill

/** The list of older Wallet records a rule would change, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class BackfillScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val out = "build/outputs/roborazzi"
    private val categories = listOf(
        CategoryEntity("restaurant", "Restaurant, fast-food", null, groupId = "food", groupName = "Food & Drinks"),
        CategoryEntity("groceries", "Groceries", null, groupId = "food", groupName = "Food & Drinks"),
    )

    @Before fun seed() = runBlocking {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        val db = AppDatabase.get(app)
        thread { db.clearAllTables() }.join()
        db.dao().replaceCategories(categories)
        db.dao().insertUserRule(UserRuleEntity(
            pattern = "Wolt", matchType = "CONTAINS", appliesTo = "EXPENSE",
            categoryId = "restaurant", categoryName = "Food & Drinks › Restaurant, fast-food",
        ))
        Unit
    }

    @Test fun list() {
        val items = listOf(
            RuleBackfill.Item("r1", "WOLT*TBILISI", "2026-10-12T18:40:00Z", -38.5, "GEL", null, "Unknown expense"),
            RuleBackfill.Item("r2", "Wolt Market", "2026-10-03T09:12:00Z", -64.2, "GEL", "groceries", "Groceries"),
            RuleBackfill.Item("r3", "WOLT*BATUMI", "2026-08-21T20:05:00Z", -22.0, "GEL", null, "Unknown expense"),
            RuleBackfill.Item("r4", "Wolt", "2026-06-14T13:30:00Z", -17.9, "GEL", null, "Unknown expense",
                error = "Record not found"),
            RuleBackfill.Item("r5", "WOLT*TBILISI", "2026-03-02T19:55:00Z", -41.0, "GEL", null, "Unknown expense"),
        )
        val state = MainViewModel.Backfill(
            UserRule("Wolt", MatchType.CONTAINS, AppliesTo.EXPENSE, "restaurant", "Food & Drinks › Restaurant, fast-food"),
            items, checked = setOf("r1", "r3", "r4", "r5"),
        )
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { BackfillScreen(state, categories, {}, {}, {}, {}) }
        }
        compose.waitForIdle()
        captureScreenRoboImage("$out/backfill-list.png")
    }

    /** The list as the app shows it, over the whole screen, on a tall phone in dark mode. */
    @Config(qualifiers = "w412dp-h920dp-night-xxhdpi")
    @Test fun overApp() {
        val vm = MainViewModel(app)
        val item = RuleBackfill.Item("r1", "KHARISKHI 777 LLC", "2026-10-20T09:12:00Z", -13.0, "GEL", null, "Unknown expense")
        @Suppress("UNCHECKED_CAST")
        val flow = MainViewModel::class.java.getDeclaredField("_backfill").apply { isAccessible = true }.get(vm)
            as kotlinx.coroutines.flow.MutableStateFlow<MainViewModel.Backfill?>
        flow.value = MainViewModel.Backfill(
            UserRule("KHARISKHI 777 LLC", MatchType.EXACT, AppliesTo.ANY, "restaurant", "Food & Drinks"),
            listOf(item), checked = setOf("r1"),
        )
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { App(vm) } }
        compose.waitUntilAtLeastOneExists(hasText("Apply (1)"), 5_000)
        captureScreenRoboImage("$out/backfill-over-app.png")
    }

    @Test fun ruleDialogButton() {
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Rules"), 5_000)
        compose.onAllNodesWithText("Rules")[0].performClick()
        compose.waitUntilAtLeastOneExists(hasText("Payee contains “Wolt”", substring = true), 5_000)
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText("Payee contains “Wolt”", substring = true)[0].performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/backfill-rule-dialog.png")
    }
}
