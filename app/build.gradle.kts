plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "su.iho.tbc2wallet"
    compileSdk = 35

    defaultConfig {
        applicationId = "su.iho.tbc2wallet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // One shared key so APKs built on any machine or CI run install over each other (Android refuses an
    // update signed with a different key). The repo is public, so the key stays out of git: it lives in
    // app/signing/ locally and in the SIGNING_KEYSTORE_B64 secret on CI. Without it, the build falls back
    // to the machine's own debug key, and that APK won't install over one signed with the shared key.
    val sharedKeystore = file("signing/tbc2wallet.keystore")
    signingConfigs {
        getByName("debug") {
            if (sharedKeystore.exists()) {
                storeFile = sharedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                logger.warn("app/signing/tbc2wallet.keystore not found: signing with this machine's debug key")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign release with the debug key so `assembleRelease` just works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("roborazzi.test.record", "true") }
    }
}

// The screenshots/ tests render many screens with Robolectric and are slow, so they only run on request:
//   ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*screenshots*'
// Images land in app/build/outputs/roborazzi/.
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) exclude("**/screenshots/**")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
