// file: app/build.gradle.kts
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* ============================================================================
 * Shared helpers (Properties / env / quoting)
 * ========================================================================== */

/** True when running under CI (GitHub Actions sets CI=true). */
val isCi: Boolean = System.getenv("CI")?.equals("true", ignoreCase = true) == true

/**
 * Load local.properties once.
 *
 * This file is developer-local and should NOT be committed.
 * We use it for safe overrides (appId, local tokens, version overrides, etc.).
 */
val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Resolve a property from (highest priority first):
 *  1) Gradle project property: -Pname=value  OR  ~/.gradle/gradle.properties
 *  2) local.properties (developer-local)
 *  3) default
 *
 * This keeps CI reproducible while allowing local convenience overrides.
 */
fun prop(name: String, default: String = ""): String =
    (project.findProperty(name) as String?)
        ?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)
            ?.takeIf { it.isNotBlank() }
        ?: default

/**
 * Escape a string literal for BuildConfig fields.
 *
 * This is required because buildConfigField takes a raw Java literal string,
 * not a Kotlin string.
 */
fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Sanitize versionName for Android + Git tags:
 * - Must not contain spaces
 * - Keep it short and predictable for log aggregation
 */
fun sanitizeVersionName(raw: String): String =
    raw.trim()
        .replace("\\s+".toRegex(), "") // versionName must be tag-safe
        .take(64) // defensive bound to avoid insane strings

/**
 * Resolve versionName with explicit precedence:
 *  1) -Papp.versionName=...
 *  2) env CI_APP_VERSION_NAME
 *  3) local.properties app.versionName=...
 *  4) fallback
 */
fun resolveVersionName(): String {
    val fromGradle = (project.findProperty("app.versionName") as String?)?.trim()
    val fromEnv = System.getenv("CI_APP_VERSION_NAME")?.trim()
    val fromLocal = prop("app.versionName").trim()

    val raw = when {
        !fromGradle.isNullOrBlank() -> fromGradle
        !fromEnv.isNullOrBlank() -> fromEnv
        fromLocal.isNotBlank() -> fromLocal
        else -> "0.0.1"
    }
    return sanitizeVersionName(raw)
}

/**
 * Resolve versionCode with explicit precedence:
 *  1) -Papp.versionCode=...
 *  2) env CI_VERSION_CODE
 *  3) env GITHUB_RUN_NUMBER (nice CI default)
 *  4) fallback
 */
fun resolveVersionCode(): Int {
    val fromGradle = (project.findProperty("app.versionCode") as String?)?.toIntOrNull()
    val fromEnv = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val fromRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    return fromGradle ?: fromEnv ?: fromRunNumber ?: 1
}

/* ============================================================================
 * Setup tasks (Submodule / Model download)
 * ========================================================================== */

/**
 * Initialize git submodules recursively.
 *
 * Behavior:
 * - Only runs when the expected submodule directory is missing/empty.
 * - Fails in CI (so you immediately see the root cause).
 * - Warns locally (so devs can still open the project even if git is weird).
 *
 * Notes:
 * - IMPORTANT: Use rootProject paths (submodules usually live at repo root).
 * - IMPORTANT: Always ignore exit value so we can print captured output,
 *   then fail with a clear GradleException.
 */
tasks.register<Exec>("checkSubmodule") {
    description = "Recursively initialize the native submodule if not yet set up"
    group = "setup"

    // Root-based path (NOT app/).
    val subDir = rootProject.layout.projectDirectory.dir("nativelib/whisper_core").asFile

    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()

    onlyIf {
        val missing = !(subDir.exists() && subDir.listFiles()?.isNotEmpty() == true)
        if (missing) logger.lifecycle("🔄 Submodule not initialized → running git submodule update --init --recursive")
        missing
    }

    workingDir = rootProject.projectDir
    commandLine("git", "submodule", "update", "--init", "--recursive")
    environment("GIT_TERMINAL_PROMPT", "0")

    // Always ignore so doLast can print buffered logs.
    isIgnoreExitValue = true

    standardOutput = out
    errorOutput = err

    doLast {
        val stdout = out.toString().trim()
        val stderr = err.toString().trim()
        if (stdout.isNotEmpty()) logger.lifecycle(stdout)
        if (stderr.isNotEmpty()) logger.warn(stderr)

        val exit = executionResult.orNull?.exitValue ?: 0
        if (exit != 0) {
            val msg = "Submodule init failed (exit=$exit)."
            if (isCi) throw GradleException("$msg See logs above.")
            logger.warn("⚠️ $msg Continuing locally.")
        } else {
            logger.lifecycle("✅ Submodule check completed.")
        }
    }
}

/**
 * Download models via a project-local script.
 *
 * Controls:
 * - Skip via -PskipModelDownload=true (recommended for CI if already handled)
 * - Skip via env SKIP_MODEL_DOWNLOAD=1
 *
 * Notes:
 * - The script should be idempotent (download only missing files).
 * - Token can be forwarded via env HF_TOKEN when needed.
 * - We look for the script in app/ first, then repo root as a fallback.
 * - Always ignore exit value so we can print captured output, then fail clearly.
 */
tasks.register<Exec>("downloadModel") {
    description = "Run the model download script safely"
    group = "setup"

    val scriptInModule = file("download_models.sh")
    val scriptInRoot = rootProject.file("download_models.sh")
    val script = when {
        scriptInModule.exists() -> scriptInModule
        scriptInRoot.exists() -> scriptInRoot
        else -> scriptInModule // placeholder; onlyIf will skip
    }

    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()

    val skipByProp = prop("skipModelDownload", "false").equals("true", ignoreCase = true)
    val skipByEnv = System.getenv("SKIP_MODEL_DOWNLOAD")?.trim() == "1"

    onlyIf {
        if (skipByProp || skipByEnv) {
            logger.lifecycle("⏭️ Model download skipped (skipModelDownload/SKIP_MODEL_DOWNLOAD).")
            return@onlyIf false
        }
        if (!script.exists()) {
            logger.warn("⚠️ download_models.sh not found (app/ or repo root). Skipping model download.")
            return@onlyIf false
        }
        true
    }

    doFirst {
        if (!script.canExecute()) {
            logger.lifecycle("🔧 Adding execute permission to download_models.sh")
            script.setExecutable(true)
        }

        /**
         * Forward tokens as environment variables if your script expects them.
         * This keeps the script logic simple and CI-friendly.
         */
        val hfToken = prop("HF_TOKEN").trim()
        if (hfToken.isNotBlank()) {
            environment("HF_TOKEN", hfToken)
        }
    }

    // Run from the directory that contains the script.
    workingDir = script.parentFile
    commandLine("bash", script.absolutePath)

    // Always ignore so doLast can print buffered logs.
    isIgnoreExitValue = true

    standardOutput = out
    errorOutput = err

    doLast {
        val stdout = out.toString().trim()
        val stderr = err.toString().trim()
        if (stdout.isNotEmpty()) logger.lifecycle(stdout)
        if (stderr.isNotEmpty()) logger.warn(stderr)

        val exit = executionResult.orNull?.exitValue ?: 0
        if (exit != 0) {
            throw GradleException("Model download failed (exit=$exit). See logs above.")
        }
        logger.lifecycle("🎉 Model download task finished.")
    }
}

/**
 * Ensure setup tasks run before Android preBuild.
 *
 * This guarantees:
 * - native submodule is present
 * - model assets are available (unless explicitly skipped)
 */
tasks.named("preBuild").configure {
    dependsOn("checkSubmodule", "downloadModel")
}

/* ============================================================================
 * Android config
 * ========================================================================== */

android {
    /**
     * Single source of truth for appId (override via local.properties: appId=...).
     * Keep namespace aligned unless you have a deliberate reason to split them.
     */
    val appId = prop("appId", "com.negi.survey")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36

        /**
         * Versioning:
         * - versionName is a tag-safe string intended to match CI release tags.
         * - versionCode is a monotonically increasing int (Play Store requirement).
         */
        val resolvedVersionName = resolveVersionName()
        val resolvedVersionCode = resolveVersionCode()

        versionName = resolvedVersionName
        versionCode = resolvedVersionCode

        /**
         * Human-readable display version for UI/logs.
         * Always use quote(...) to avoid breaking BuildConfig on special chars.
         */
        val displayVersion = "$resolvedVersionName with WhisperCpp"
        buildConfigField("String", "DISPLAY_VERSION", quote(displayVersion))

        /** AndroidX Test Runner (required for Orchestrator). */
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        /**
         * Instrumentation args to reduce flakiness on Android 14+:
         * - clearPackageData: isolates state between tests (good with Orchestrator)
         * - useTestStorageService: avoids legacy external storage behavior
         * - numShards=1: prevents accidental parallel sharding unless explicitly overridden
         */
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        testInstrumentationRunnerArguments["numShards"] = "1"
    }

    /** Always run androidTest against the debug build (stable applicationId). */
    testBuildType = "debug"

    testOptions {
        /**
         * ANDROIDX_TEST_ORCHESTRATOR:
         * - each test runs in its own Instrumentation instance
         * - drastically reduces shared-state flakiness
         */
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        // unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        /** Keep Java 17 toolchain consistent with Kotlin jvmTarget. */
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /* ============================================================================
     * Kotlin compiler options (migrated from android.kotlinOptions{})
     * ========================================================================== */

    kotlin {
        jvmToolchain(17)

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
        }
    }

    buildTypes {
        debug {
            /** Avoid applicationIdSuffix to keep MediaStore ownership stable. */
            buildConfigField("String", "GH_OWNER", quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO", quote("SurveyExports"))
            buildConfigField("String", "GH_BRANCH", quote("main"))
            buildConfigField("String", "GH_PATH_PREFIX", quote(""))
            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField("String", "GH_OWNER", quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO", quote("SurveyExports"))
            buildConfigField("String", "GH_BRANCH", quote("main"))
            buildConfigField("String", "GH_PATH_PREFIX", quote(""))
            buildConfigField("String", "GH_TOKEN", quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN", quote(prop("HF_TOKEN")))

            /**
             * Debug signing for CI/dev convenience.
             * For production distribution, switch to a proper release keystore.
             */
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    /**
     * Broad META-INF excludes to avoid conflicts among OkHttp/Coroutines/Media3/MediaPipe, etc.
     * Prefer excludes over pickFirst to reduce hidden runtime surprises.
     */
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

/* ============================================================================
 * Dependencies
 * ========================================================================== */

dependencies {
    implementation(project(":nativelib"))

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.room.ktx)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime.saveable)

    // Debug/Preview-only
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Navigation 3
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // Kotlin / Coroutines / Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // Core / AppCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Activity / Navigation / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)
    implementation(libs.androidx.lifecycle.process)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Security
    implementation(libs.androidx.security.crypto)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // MediaPipe GenAI + LiteRT-LM
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.litertlm)

    // Accompanist
    implementation(libs.accompanist.navigation.animation)

    // SAF (androidTest uses DocumentFile)
    androidTestImplementation(libs.androidx.documentfile)

    // Test libs
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    // AndroidX Test
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)

    // Orchestrator runner + orchestrator itself
    androidTestImplementation(libs.androidx.test.runner)
    androidTestUtil(libs.androidx.test.orchestrator)
}

/* ============================================================================
 * Diagnostic tasks
 * ========================================================================== */

/**
 * Print resolved instrumentation runner arguments.
 *
 * Usage:
 *  ./gradlew :app:printAndroidTestArgs
 */
tasks.register("printAndroidTestArgs") {
    group = "verification"
    description = "Print resolved default instrumentation runner arguments."
    doLast {
        println("=== Default Instrumentation Args ===")
        val args = android.defaultConfig.testInstrumentationRunnerArguments
        args.forEach { (k, v) -> println(" - $k = $v") }
        println("===================================")
        println("Override example: -Pandroid.testInstrumentationRunnerArguments.numShards=2")
    }
}

/**
 * Fail if more than one device is connected.
 *
 * This prevents duplicate test runs caused by multiple attached devices/emulators.
 * Note: Requires adb to be available on PATH.
 */
tasks.register("checkSingleConnectedDevice") {
    group = "verification"
    description = "Fails if more than one device is connected (helps avoid double runs)."
    doLast {
        val adbCheck = ProcessBuilder("bash", "-lc", "command -v adb >/dev/null 2>&1").start()
        adbCheck.waitFor()
        if (adbCheck.exitValue() != 0) {
            throw GradleException("adb is not available on PATH. Install Android platform-tools.")
        }

        val process = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val lines = out.lineSequence()
            .drop(1) // skip header
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("\tdevice") }
            .toList()

        println("Connected devices: ${lines.size}")
        lines.forEach { println(" - $it") }

        if (lines.size > 1) {
            throw GradleException(
                "More than one device/emulator is connected. Keep exactly one to avoid duplicate test runs."
            )
        }
    }
}

/**
 * Print all assets included in src/main/assets.
 *
 * Usage:
 *  ./gradlew :app:printAssets
 */
tasks.register("printAssets") {
    group = "diagnostic"
    description = "Print all assets included in src/main/assets"
    doLast {
        val assetsDir = file("src/main/assets")
        if (!assetsDir.exists()) {
            println("⚠️ No assets directory found!")
            return@doLast
        }

        val files = assetsDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            println("⚠️ Assets directory is empty.")
        } else {
            println("📦 Found ${files.size} asset files under: ${assetsDir.absolutePath}")
            files.forEach { f ->
                println("  - ${f.relativeTo(assetsDir)} (${f.length()} bytes)")
            }
        }
    }
}
