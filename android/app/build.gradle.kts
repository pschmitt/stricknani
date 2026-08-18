plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val configuredVersionCode =
    providers
        .gradleProperty("versionCode")
        .orElse("1")
        .map { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: error("versionCode must be a positive integer")
        }
        .get()
val gitRevision = System.getenv("GIT_REVISION") ?: "unknown"
val inferredVersionName =
    Regex("""^v?(\d+\.\d+\.\d+)(?:[-+].*)?$""").matchEntire(gitRevision)?.groupValues?.get(1)
val configuredVersionName =
    providers.gradleProperty("versionName").orElse(inferredVersionName ?: "0.1.0").get().also { name
        ->
        require(name.isNotBlank()) { "versionName must not be blank" }
    }

android {
    namespace = "blue.anika.wolle"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "blue.anika.wolle"
        minSdk = 26
        targetSdk = 36

        versionCode = configuredVersionCode
        versionName = configuredVersionName
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
        val buildDate = System.getenv("BUILD_DATE") ?: "unknown"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only overridden in CI (see .github/workflows/release.yaml, once it exists - SNA-12),
        // which decodes a persistent keystore from a secret and exports CI_KEYSTORE_PATH - local
        // debug builds keep using the regular auto-generated ~/.android/debug.keystore. Without
        // this, every CI run signs with a different ephemeral debug key, which breaks update
        // checks for anyone installing builds via Obtainium (signature mismatch on every
        // release). Matches the sibling apps' pattern (see e.g. syncwich's app/build.gradle.kts).
        named("debug") {
            System.getenv("CI_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        named("debug") { applicationIdSuffix = ".debug" }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)

    // Compose / Material 3 (Material You dynamic color)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Stricknani API client (SNA-6 onward)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // Project/yarn photo loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Offline cache (SNA-7)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted server URL + API token storage, plus plain prefs (last sync time, UI prefs)
    implementation(libs.androidx.security.crypto)
    // AndroidX Security pulls Tink's annotations as a compile-time dependency; keep them on the
    // release shrinker's classpath so R8 can resolve the referenced annotations.
    implementation(libs.errorprone.annotations)
    implementation(libs.androidx.datastore.preferences)

    // Background sync (SNA-7)
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.fastlane.screengrab)
}
