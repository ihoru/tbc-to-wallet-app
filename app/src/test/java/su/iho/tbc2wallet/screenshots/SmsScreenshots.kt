package su.iho.tbc2wallet.screenshots

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.iho.tbc2wallet.App
import su.iho.tbc2wallet.MainViewModel
import su.iho.tbc2wallet.data.AppDatabase
import su.iho.tbc2wallet.data.TransactionEntity
import su.iho.tbc2wallet.data.TxSource
import su.iho.tbc2wallet.data.TxStatus
import su.iho.tbc2wallet.ingest.TbcNotificationListener

/** TBC SMS: the Activity tab marks how each one came in; Settings asks for the SMS permission. Run with -Pscreenshots. */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class SmsScreenshots {
    @get:Rule val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db by lazy { AppDatabase.get(app) }
    private val out = "build/outputs/roborazzi"

    @Before fun seed() = runBlocking {
        thread { db.clearAllTables() }.join()
        val me = ComponentName(app, TbcNotificationListener::class.java).flattenToString()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", me)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dao = db.dao()
        val now = System.currentTimeMillis()
        dao.insertIfNew(TransactionEntity(
            dedupKey = "s1", kind = "INCOME", amount = "60.00", currency = "GEL", counterParty = "პირადი გადარიცხვა",
            accountLabel = "GE61TB****0007", cardLast4 = null, recordDateUtc = "2026-11-12T07:24:26Z",
            rawText = "Received Amount: 60.00 GEL\nGE61TB****0007\n2026/11/12 11:24:26\nDescription: პირადი გადარიცხვა",
            status = TxStatus.SENT, walletRecordId = "r1", categoryName = "Unknown income", receivedAt = now, source = TxSource.SMS,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "s2", kind = "EXPENSE", amount = "35.00", currency = "GEL", counterParty = "Account refill (magti)",
            accountLabel = "ID:591006815", cardLast4 = null, recordDateUtc = "2026-11-12T06:10:00Z",
            rawText = "Mobile Balance Recharge\n35.00GEL\nAccount refill (magti)\nID:591006815\n12/11/2026",
            status = TxStatus.SENT, walletRecordId = "r2", categoryId = "phone", categorySource = "USER_RULE",
            categoryName = "Communication › Phone", receivedAt = now - 1, source = TxSource.SMS,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "p1", kind = "EXPENSE", amount = "42.10", currency = "GEL", counterParty = "Shopsy",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-11-11T19:28:00Z",
            rawText = "42.10 GEL\nTBC CARD VISA GOLD (***1234)\nShopsy 11/11/26 23:28\nBalance: 1500.00 GEL",
            status = TxStatus.SENT, walletRecordId = "r3", categoryId = "groceries", categorySource = "USER_RULE",
            categoryName = "Food & Drinks › Groceries", receivedAt = now - 2, alsoVia = TxSource.SMS,
        ))
        dao.insertIfNew(TransactionEntity(
            dedupKey = "p2", kind = "EXPENSE", amount = "12.50", currency = "GEL", counterParty = "Coffee Lab",
            accountLabel = "TBC CARD VISA GOLD (***1234)", cardLast4 = "1234", recordDateUtc = "2026-11-11T08:05:00Z",
            rawText = "12.50 GEL\nTBC CARD VISA GOLD (***1234)\nCoffee Lab 11/11/26 12:05\nBalance: 1542.10 GEL",
            status = TxStatus.SENT, walletRecordId = "r4", categoryId = "cafe", categorySource = "USER_RULE",
            categoryName = "Food & Drinks › Bar, cafe", receivedAt = now - 3,
        ))
        Unit
    }

    @Test fun activity() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("wallet_token_enc", "sample").commit()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Shopsy"), 5_000)
        captureScreenRoboImage("$out/sms-activity.png")
    }

    @Test fun settings() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("wallet_token_enc").commit()
        compose.setContent { MaterialTheme(colorScheme = lightColorScheme()) { App(MainViewModel(app)) } }
        compose.waitUntilAtLeastOneExists(hasText("Allow reading SMS from TBC"), 5_000)
        captureScreenRoboImage("$out/sms-settings-permission.png")
        compose.onNodeWithText("Save senders").performScrollTo()
        compose.waitForIdle()
        captureScreenRoboImage("$out/sms-settings-senders.png")
    }
}
