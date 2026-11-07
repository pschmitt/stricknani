import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.sentry) apply false
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
// CI and release builds export GIT_REVISION. Remote builds from `just` (the synced tree has no
// .git) pass the revision of the machine running `just` as -PgitRevision=<sha>[-dirty].
val gitRevision =
    (System.getenv("GIT_REVISION") ?: providers.gradleProperty("gitRevision").orNull)
        ?.trim()
        ?.takeIf { it.matches(Regex("[A-Za-z0-9._+-]+")) } ?: "unknown"
// The Play bundle must not ship the GitHub-APK self-update card (Play apps update through Play), so
// it is compiled out when building a bundle; every APK build keeps it. `-PselfUpdate=true|false`
// overrides the detection.
val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
val selfUpdateEnabled =
    providers.gradleProperty("selfUpdate").map { it.toBoolean() }.orElse(!isBuildingBundle).get()
// Optional Sentry error reporting is in every variant, Google Play bundles included: it is
// operator-configured, goes through the server's tunnel, the user can switch it off, and the Play
// data safety form declares it. It is independent of self-update. `-PerrorReporting=false` opts
// out of the SDK, its source set and the toggle.
val errorReportingEnabled =
    providers.gradleProperty("errorReporting").map { it.toBoolean() }.orElse(true).get()
val instrumentationBuildType =
    providers.gradleProperty("instrumentationBuildType").orElse("debug").get()
// Debug builds carry the extras (purple branding, debug version label, developer mode on by
// default). `-PdebugExtras=false` turns them off on a debug build, for Play screenshots.
val debugExtras = providers.gradleProperty("debugExtras").map { it.toBoolean() }.orElse(true).get()
// Where the build came from, shown on About: `latest` (rolling prerelease built from main),
// `stable` (a semantic-version tag), `local` (no CI: `just build`, Android Studio) or `play` (a
// Play bundle: the shared Play workflow cannot pass the property, so bundles infer it).
// release.yaml passes -PreleaseChannel=latest|stable for the GitHub release APKs.
val releaseChannel =
    providers
        .gradleProperty("releaseChannel")
        .orElse(if (isBuildingBundle) "play" else "local")
        .get()
        .also { value ->
            require(value in setOf("latest", "stable", "local", "play")) {
                "releaseChannel must be latest, stable, local or play"
            }
        }
// When the APK was built (UTC, ISO-8601, minute precision): CI exports BUILD_DATE; otherwise
// -PbuildDate=<iso> or the time of this Gradle run.
val buildDate =
    (System.getenv("BUILD_DATE") ?: providers.gradleProperty("buildDate").orNull)?.trim()?.takeIf {
        it.matches(Regex("[0-9TZ:+.-]+"))
    } ?: Instant.now().truncatedTo(ChronoUnit.MINUTES).toString()
val inferredVersionName =
    Regex("""^v?(\d+\.\d+\.\d+)(?:[-+].*)?$""").matchEntire(gitRevision)?.groupValues?.get(1)
val configuredVersionName =
    providers.gradleProperty("versionName").orElse(inferredVersionName ?: "1.0.0").get().also { name
        ->
        require(name.isNotBlank()) { "versionName must not be blank" }
    }

android {
    namespace = "blue.anika.wolle"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    testBuildType = instrumentationBuildType

    defaultConfig {
        applicationId = "blue.anika.wolle"
        minSdk = 26
        targetSdk = 36

        versionCode = configuredVersionCode
        versionName = configuredVersionName
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", selfUpdateEnabled.toString())
        buildConfigField("boolean", "ERROR_REPORTING_COMPILED", errorReportingEnabled.toString())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        buildConfigField("String", "RELEASE_CHANNEL", "\"$releaseChannel\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The install permissions and receivers of the in-app updater live in their own manifest,
    // merged into the debug and release builds whenever self-update is on. The Play bundle
    // (-PselfUpdate=false or any `bundle` task) never gets them.
    if (selfUpdateEnabled) {
        listOf("debug", "release").forEach { name ->
            sourceSets.getByName(name).apply {
                manifest.srcFile("src/selfupdate/AndroidManifest.xml")
                kotlin.directories.add("src/selfupdate/kotlin")
            }
        }
    }

    // The Sentry-backed reporter lives in its own source set, compiled unless error reporting is
    // opted out of with -PerrorReporting=false. Shared code sees the ErrorReporter interface and,
    // without it, NoErrorReporter.
    if (errorReportingEnabled) {
        listOf("debug", "release").forEach { name ->
            sourceSets.getByName(name).kotlin.directories.add("src/errorreporting/kotlin")
        }
        sourceSets.getByName("test").kotlin.directories.add("src/errorreporting-test/kotlin")
    }

    // Room's exported schemas (one JSON per database version) feed the migration test.
    sourceSets.getByName("androidTest").assets.directories.add("schemas")

    // The minified release instrumentation suite intentionally excludes debug-only integration
    // fixtures. Debug keeps the regular androidTest sources unchanged.
    if (instrumentationBuildType == "release") {
        sourceSets.getByName("androidTest").kotlin.directories.apply {
            clear()
            add("src/minifiedAndroidTest/kotlin")
        }
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
        // -PappIdSuffix=.shots installs a side-by-side copy (own data) for screenshot runs on a
        // phone that already has the real debug app signed in.
        named("debug") {
            applicationIdSuffix = (project.findProperty("appIdSuffix") as String?) ?: ".debug"
            buildConfigField("boolean", "DEBUG_EXTRAS", debugExtras.toString())
        }
        named("release") {
            applicationIdSuffix = project.findProperty("appIdSuffix") as String?
            buildConfigField("boolean", "DEBUG_EXTRAS", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (instrumentationBuildType == "release") {
                proguardFiles("src/minifiedAndroidTest/proguard-rules.pro")
            }
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
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

    // Markdown rendering for descriptions/notes/steps (SNA-22) - the -coil3 artifact resolves
    // inline images through the same SingletonImageLoader (StricknaniApp.kt) every other image in
    // the app already uses, so it picks up the authenticated @MediaClient loader automatically.
    implementation(libs.markdown.renderer)
    implementation(libs.jetbrains.markdown)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.coil3)

    // QR-code onboarding scanner (SNA-13) - CameraX + ZXing, no Google Play Services dependency
    // (matches nyetbox's/jollyfin's scanner stack exactly).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

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

    // Optional error reporting, in every variant (opt out with -PerrorReporting=false).
    if (errorReportingEnabled) {
        implementation(libs.sentry.android)
    }

    // Shizuku silent installs (SNA-88): GitHub APK builds only, never in the Play bundle.
    if (selfUpdateEnabled) {
        listOf("debugImplementation", "releaseImplementation").forEach { configuration ->
            add(configuration, libs.shizuku.api)
            add(configuration, libs.shizuku.provider)
            add(configuration, libs.hiddenapibypass)
        }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.fastlane.screengrab)
}

// R8 mappings for Sentry: in minified builds the plugin embeds a ProGuard UUID, and when CI
// provides SENTRY_AUTH_TOKEN it uploads the mapping under it, so release stack traces read as
// source. Everything else it offers (installing the SDK, bytecode instrumentation, source context,
// dependency reports, telemetry) stays off: the app sets the SDK up itself and sends nothing extra.
if (errorReportingEnabled) {
    apply(plugin = "io.sentry.android.gradle")
    extensions.configure<io.sentry.android.gradle.extensions.SentryPluginExtension> {
        org.set("brknlol")
        projectName.set("stricknani")
        includeProguardMapping.set(true)
        autoUploadProguardMapping.set(!System.getenv("SENTRY_AUTH_TOKEN").isNullOrBlank())
        uploadNativeSymbols.set(false)
        includeNativeSources.set(false)
        includeSourceContext.set(false)
        includeDependenciesReport.set(false)
        telemetry.set(false)
        ignoredBuildTypes.set(setOf("debug"))
        tracingInstrumentation { enabled.set(false) }
        autoInstallation { enabled.set(false) }
    }
}
