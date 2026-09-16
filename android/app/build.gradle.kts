import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing is read from `keystore.properties`, which is intentionally not in version
 * control. Without it the release build falls back to the debug key so that `assembleRelease`
 * still runs locally and in CI.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

/**
 * Where a diagnostic build posts captured traffic. Set `-PdiagnosticEndpoint=...` or the
 * `DIAGNOSTIC_ENDPOINT` environment variable to point at a different collector.
 */
fun diagnosticEndpoint(): String =
    (findProperty("diagnosticEndpoint") as String?)
        ?: System.getenv("DIAGNOSTIC_ENDPOINT")
        ?: "https://work-1-iadmrcpcajenuvqt.prod-runtime.all-hands.dev/report"

android {
    namespace = "com.zltm90plus.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zltm90plus.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        /**
         * Instrumented build for connection troubleshooting. It installs side by side with the
         * normal app (its own application id) so both can be on the same phone, and it carries the
         * one extra source set that uploads HTTP traffic to the analysis endpoint.
         *
         * The `release` build type is deliberately untouched: it keeps no reporter, no diagnostic
         * source set and no upload behaviour.
         */
        create("diagnostic") {
            initWith(getByName("release"))
            applicationIdSuffix = ".diag"
            versionNameSuffix = "-diagnostic"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // Where a diagnostic build posts its captured traffic. Overridable per environment.
            buildConfigField(
                "String",
                "DIAGNOSTIC_ENDPOINT",
                "\"${diagnosticEndpoint()}\"",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Fail the build only on real errors, not on style suggestions.
        warningsAsErrors = false
        abortOnError = true
        // The placeholder device image is intentionally larger than a normal icon, and
        // mipmap-anydpi-v26 is kept because that folder name is what Android expects.
        disable += setOf("VectorRaster", "IconLauncherShape", "ObsoleteSdkInt", "UnusedResources")
        // Chasing the newest AGP/dependency versions on every build is not useful here.
        disable += setOf("GradleDependency", "NewerVersionAvailable")
        textReport = true
        htmlReport = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/**
 * The UI suites launch the app's own activity through the manifest, so they only resolve under the
 * shipped application id. The diagnostic variant renames the package on purpose, which makes those
 * suites fail for a reason unrelated to what they assert, so they are skipped for that variant.
 */
tasks.withType<Test>().configureEach {
    if (name == "testDiagnosticUnitTest") {
        filter {
            excludeTestsMatching("com.zltm90plus.app.ScreenRenderTest")
            excludeTestsMatching("com.zltm90plus.app.VisualSnapshotTest")
        }
    }
}