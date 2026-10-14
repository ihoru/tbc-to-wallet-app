package su.iho.tbc2wallet.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.iho.tbc2wallet.CategoryPicker
import su.iho.tbc2wallet.data.CategoryEntity

/** Renders the Wallet-style category picker with Wallet-shaped sample data. Run with -Pscreenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class CategoryPickerScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = "build/outputs/roborazzi"

    companion object {
        private fun group(id: String, name: String, color: String, vararg cats: String) =
            listOf(CategoryEntity(id, name, null, groupId = id, groupName = name, color = color, systemId = id)) +
                cats.map { c ->
                    val sys = "${id}__" + c.lowercase().replace(Regex("[^a-z]+"), "_").trim('_')
                    CategoryEntity(sys, c, null, groupId = id, groupName = name, color = color, systemId = sys)
                }

        private fun custom(id: String, name: String, parentId: String, parentName: String, color: String? = null) =
            CategoryEntity(id, name, parentName, parentId = parentId, groupId = "food_and_drinks", groupName = "Food & Drinks", color = color)

        val SAMPLE = group("food_and_drinks", "Food & Drinks", "#FF3D00", "Bar, cafe", "Groceries", "Restaurant, fast-food") +
            group("shopping", "Shopping", "#4FC3F7", "Clothes & shoes", "Drug-store, chemist", "Electronics, accessories") +
            group("housing", "Housing", "#FFA726", "Energy, utilities", "Rent") +
            group("transportation", "Transportation", "#78909C", "Public transport", "Taxi") +
            group("vehicle", "Vehicle", "#AA00FF", "Fuel", "Parking") +
            group("life_and_entertainment", "Life & Entertainment", "#64DD17", "Fitness, sport") +
            group("communication_pc", "Communication, PC", "#536DFE", "Internet", "Phone, cell phone") +
            group("financial_expenses", "Financial expenses", "#00BFA5", "Taxes", "Charges, Fees") +
            group("investments", "Investments", "#FF4081", "Savings") +
            group("income", "Income", "#FFD600", "Wage, invoices") +
            group("others", "Others", "#9E9E9E") +
            custom("tea", "Tea", "food_and_drinks__bar_cafe", "Bar, cafe") +
            custom("sweets", "Sweets", "food_and_drinks__groceries", "Groceries", "#26C6DA") +
            custom("veg", "Vegetables", "food_and_drinks__groceries", "Groceries") +
            custom("meat", "Meat", "food_and_drinks__groceries", "Groceries") +
            custom("bakery", "Bakery", "food_and_drinks__groceries", "Groceries") +
            custom("wolt", "Wolt", "food_and_drinks__restaurant_fast_food", "Restaurant, fast-food") +
            custom("glovo", "Glovo", "food_and_drinks__restaurant_fast_food", "Restaurant, fast-food")
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { content() } }
        compose.onRoot().captureRoboImage("$out/category-picker-$name.png")
    }

    @Test fun allCategories() = shot("1-all") { CategoryPicker(SAMPLE, {}, {}) }

    @Test fun group() = shot("2-group") { CategoryPicker(SAMPLE, {}, {}, initialPath = listOf("group:food_and_drinks")) }

    @Test fun category() = shot("3-category") {
        CategoryPicker(SAMPLE, {}, {}, initialPath = listOf("group:food_and_drinks", "food_and_drinks__bar_cafe"))
    }

    @Test fun search() = shot("4-search") { CategoryPicker(SAMPLE, {}, {}, initialQuery = "re") }
}
