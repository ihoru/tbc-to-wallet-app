package su.iho.tbc2wallet.screenshots

import android.app.Application
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
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.UserRuleEntity

/** Renders the Rules tab with rules added in non-alphabetical order: the newest one is listed first. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp-xhdpi")
class NewestRulesFirstScreenshots {
    @get:Rule val compose = createComposeRule()

    @Before fun seed() = runBlocking {
        val db = AppDatabase.get(ApplicationProvider.getApplicationContext())
        thread { db.clearAllTables() }.join()
        // Inserted oldest → newest.
        listOf(
            "Bolt" to "Transportation › Taxi",
            "Wolt" to "Food & Drinks › Restaurant, fast-food",
            "Carrefour" to "Food & Drinks › Groceries",
            "Apple.com" to "Life & Entertainment › Software",
            "Gulf" to "Vehicle › Fuel",
        ).forEach { (pattern, category) ->
            db.dao().insertUserRule(UserRuleEntity(pattern = pattern, matchType = "CONTAINS", appliesTo = "EXPENSE",
                categoryId = pattern.lowercase(), categoryName = category))
        }
    }

    @Test fun rulesTab() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(ApplicationProvider.getApplicationContext<Application>())) }
        }
        compose.onNodeWithText("Rules").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Tap a rule to edit it."), 5_000)
        captureScreenRoboImage("build/outputs/roborazzi/newest-rules-first.png")
    }
}
