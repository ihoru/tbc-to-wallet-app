package su.iho.tbc2wallet.shot

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.iho.tbc2wallet.MainActivity
import su.iho.tbc2wallet.ingest.TbcNotificationListener

/**
 * Renders the Settings screen (the start tab when no token is saved) to app/build/shots/
 * for PR screenshots: ./gradlew :app:testDebugUnitTest --tests '*SettingsShot*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsShot {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun initWork() = WorkManagerTestInitHelper.initializeTestWorkManager(app)

    private fun grantNotificationAccess() {
        val me = ComponentName(app, TbcNotificationListener::class.java).flattenToString()
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", me)
    }

    private fun shoot(name: String) = ActivityScenario.launch(MainActivity::class.java).use {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Test fun nothingGranted() = shoot("settings-not-granted")

    @Test fun accessGrantedOnly() {
        grantNotificationAccess()
        shoot("settings-access-only")
    }

    @Test fun allGranted() {
        grantNotificationAccess()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shoot("settings-all-granted")
    }
}
