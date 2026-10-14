package su.iho.tbc2wallet.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.iho.tbc2wallet.CategoryTreeList
import su.iho.tbc2wallet.data.CategoryEntity

/** Renders the rule dialog's category picker with Wallet-shaped sample data. Run with -Pscreenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xhdpi")
class CategoryPickerScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = "build/outputs/roborazzi"

    companion object {
        private fun group(id: String, name: String, color: String, vararg cats: String) =
            listOf(CategoryEntity(id, name, null, groupId = id, groupName = name, color = color, systemId = id)) +
                cats.map { CategoryEntity("${id}__$it", it, null, groupId = id, groupName = name, color = color, systemId = "${id}__$it") }

        val SAMPLE = group("food_and_drinks", "Food & Drinks", "#EF5350", "Bar, cafe", "Groceries", "Restaurant, fast-food") +
            group("shopping", "Shopping", "#4FC3F7", "Clothes & shoes", "Drug-store, chemist", "Electronics, accessories", "Gifts, joy") +
            group("housing", "Housing", "#FFA726", "Energy, utilities", "Rent", "Services") +
            group("transportation", "Transportation", "#78909C", "Public transport", "Taxi") +
            group("vehicle", "Vehicle", "#AB47BC", "Fuel", "Parking") +
            group("life_and_entertainment", "Life & Entertainment", "#66BB6A", "Fitness, sport", "Holiday, trips, hotels") +
            group("income", "Income", "#FFCA28", "Wage, invoices") +
            group("others", "Others", "#9E9E9E") +
            CategoryEntity("custom1", "Wolt", "Restaurant, fast-food", parentId = "food_and_drinks__Restaurant, fast-food",
                groupId = "food_and_drinks", groupName = "Food & Drinks", color = "#EF5350") +
            CategoryEntity("custom2", "Glovo", "Restaurant, fast-food", parentId = "food_and_drinks__Restaurant, fast-food",
                groupId = "food_and_drinks", groupName = "Food & Drinks", color = "#EF5350")
    }

    @Composable private fun Picker(query: String) = MaterialTheme(colorScheme = lightColorScheme()) {
        Card(Modifier.width(380.dp).padding(8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, {}, label = { Text("Category") }, singleLine = true)
                CategoryTreeList(SAMPLE, query, onSelect = {})
            }
        }
    }

    @Test fun collapsed() {
        compose.setContent { Picker("") }
        compose.onRoot().captureRoboImage("$out/category-picker-collapsed.png")
    }

    @Test fun expanded() {
        compose.setContent { Picker("") }
        compose.onNodeWithContentDescription("Expand Food & Drinks").performClick()
        compose.onNodeWithContentDescription("Expand Restaurant, fast-food").performClick()
        compose.onRoot().captureRoboImage("$out/category-picker-expanded.png")
    }

    @Test fun search() {
        compose.setContent { Picker("ta") }
        compose.onRoot().captureRoboImage("$out/category-picker-search.png")
    }
}
