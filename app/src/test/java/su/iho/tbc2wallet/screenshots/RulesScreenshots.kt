package su.iho.tbc2wallet.screenshots

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import su.iho.tbc2wallet.LabelPicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollToNode
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
import su.iho.tbc2wallet.data.CategoryEntity
import su.iho.tbc2wallet.data.LabelEntity
import su.iho.tbc2wallet.data.LearnedEntity
import su.iho.tbc2wallet.data.UserRuleEntity

/** Renders the Rules tab with sample data, for screenshots in PRs. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp-xhdpi")
class RulesScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        val db = AppDatabase.get(ApplicationProvider.getApplicationContext())
        // The database instance outlives a single test, so start each one empty (not allowed on the main thread).
        thread { db.clearAllTables() }.join()
        val dao = db.dao()
        dao.replaceCategories(listOf(
            CategoryEntity("food", "Restaurant, fast-food", "Food & Drinks"),
            CategoryEntity("groceries", "Groceries", "Food & Drinks"),
            CategoryEntity("taxi", "Taxi", null, groupId = "transportation", groupName = "Transportation", color = "#455a64", systemId = "transportation__taxi"),
            CategoryEntity("fuel", "Fuel", "Vehicle"),
        ))
        dao.replaceLabels(listOf(LabelEntity("l1", "delivery"), LabelEntity("l2", "weekly"), LabelEntity("l3", "work"), LabelEntity("l4", "trip")))
        listOf(
            UserRuleEntity(pattern = "wolt", matchType = "CONTAINS", appliesTo = "EXPENSE",
                categoryId = "food", categoryName = "Food & Drinks › Restaurant, fast-food", labelIds = "l1", labelNames = "delivery"),
            UserRuleEntity(pattern = "Wolt Market", matchType = "CONTAINS", appliesTo = "ANY",
                categoryId = "groceries", categoryName = "Food & Drinks › Groceries", labelIds = "l1\nl2", labelNames = "delivery\nweekly"),
            UserRuleEntity(pattern = "Bolt", matchType = "CONTAINS", appliesTo = "EXPENSE",
                categoryId = "taxi", categoryName = "Transportation › Taxi", labelIds = "l3", labelNames = "work"),
            UserRuleEntity(pattern = "Bolt", matchType = "CONTAINS", appliesTo = "ANY",
                categoryId = "fuel", categoryName = "Vehicle › Fuel"),
        ).forEach { dao.insertUserRule(it) }
        dao.replaceLearned(listOf(
            LearnedEntity("GLOVO TBILISI", "food", "Food & Drinks › Restaurant, fast-food", 7, 8),
            LearnedEntity("GULF", "fuel", "Vehicle › Fuel", 5, 5),
            LearnedEntity("SPAR", "groceries", "Food & Drinks › Groceries", 1, 1),
        ))
    }

    private fun showRules() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(ApplicationProvider.getApplicationContext<Application>())) }
        }
        compose.onNodeWithText("Rules").performClick()
        compose.waitUntilAtLeastOneExists(hasText("These rules clash"), 5_000)
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test fun rulesTab() {
        showRules()
        captureScreenRoboImage("$out/rules-tab.png")
    }

    @Test fun learnedExpanded() {
        showRules()
        scrollTo("Learned from your history (3)")
        compose.onNodeWithText("Learned from your history (3)").performClick()
        scrollTo("Payee is “SPAR”")
        compose.waitUntilAtLeastOneExists(hasText("Payee is “GULF”"), 5_000)
        captureScreenRoboImage("$out/rules-learned-expanded.png")
    }

    @Test fun editRuleDialog() {
        showRules()
        scrollTo("Payee contains “Bolt” · expenses")
        // With the whole app on screen, opening the dialog never reports idle under Robolectric
        // (the dialog alone does), so drive the clock by hand from here.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Payee contains “Bolt” · expenses").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/rule-edit-dialog.png")
    }

    @Test fun newRuleDialog() {
        showRules()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Add rule").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/rule-new-dialog.png")
    }

    @Test fun labelDropdown() {
        // The picker on its own: finding nodes once the rule dialog is open over the app never idles.
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(24.dp)) {
                        var chosen by remember { mutableStateOf(mapOf("l1" to "delivery", "l3" to "work")) }
                        val options = listOf("l1" to "delivery", "l4" to "trip", "l2" to "weekly", "l3" to "work")
                        LabelPicker(options, chosen) { id, name -> chosen = if (id in chosen) chosen - id else chosen + (id to name) }
                    }
                }
            }
        }
        compose.onNodeWithText("delivery, work").performClick()
        compose.waitUntilAtLeastOneExists(hasText("trip"), 5_000)
        captureScreenRoboImage("$out/rule-labels-dropdown.png")
    }

    @Test fun learnedSwipe() {
        showRules()
        scrollTo("Learned from your history (3)")
        compose.onNodeWithText("Learned from your history (3)").performClick()
        scrollTo("Payee is “SPAR”")
        compose.waitUntilAtLeastOneExists(hasText("Payee is “GULF”"), 5_000)
        // Held halfway, so the delete background shows.
        compose.onNodeWithText("Payee is “GULF”").performTouchInput { down(centerRight); moveBy(Offset(-width * 0.4f, 0f)) }
        compose.mainClock.advanceTimeBy(500)
        captureScreenRoboImage("$out/learned-swipe.png")
    }

    @Test fun learnedForgetDialog() {
        showRules()
        scrollTo("Learned from your history (3)")
        compose.onNodeWithText("Learned from your history (3)").performClick()
        scrollTo("Payee is “SPAR”")
        compose.waitUntilAtLeastOneExists(hasText("Payee is “GULF”"), 5_000)
        // The dialog over the whole app never reports idle, so drive the clock by hand (see editRuleDialog).
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Payee is “GULF”").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/learned-forget-dialog.png")
    }

    @Test fun deleteRuleConfirm() {
        showRules()
        scrollTo("Payee contains “Bolt” · expenses")
        // The dialog over the whole app never reports idle, so drive the clock by hand (see editRuleDialog).
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithContentDescription("Delete rule")[2].performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/rule-delete-confirm.png")
    }

    @Test fun deleteRuleConfirmFromEdit() {
        showRules()
        scrollTo("Payee contains “Bolt” · expenses")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Payee contains “Bolt” · expenses").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        captureScreenRoboImage("$out/rule-delete-confirm-from-edit.png")
    }
}
