// file: app/build.gradle.kts
import com.android.build.api.dsl.ApplicationExtension
import java.io.ByteArrayOutputStream
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)

    // NOTE:
    // AGP 9+ uses built-in Kotlin for Android projects.
    // Do NOT apply org.jetbrains.kotlin.android (kotlin.android) here.
    // alias(libs.plugins.kotlin.android)

    // KEEP: Kotlin plugins that are still valid with built-in Kotlin.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* ============================================================================
 * Shared helpers (Properties / env / quoting)
 * ========================================================================== */

/** True when running under CI (GitHub Actions sets CI=true). */
val isCi: Boolean = System.getenv("CI")?.equals("true", ignoreCase = true) == true

/**
 * Load developer-local property files once.
 *
 * These files should NOT be committed.
 * - gradle.properties.local: optional repo-root override file (gitignored)
 * - local.properties: Android standard local override file (gitignored)
 */
val gradleLocalProps: Properties = Properties().apply {
    val f = rootProject.file("gradle.properties.local")
    if (f.exists()) f.inputStream().use { load(it) }
}

val localProps: Properties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Resolve a property from (highest priority first):
 *  1) Standard Gradle properties (-Pname=..., ~/.gradle/gradle.properties, gradle.properties, ORG_GRADLE_PROJECT_*)
 *  2) gradle.properties.local (repo-root, gitignored)
 *  3) local.properties (Android standard, gitignored)
 *  4) default
 *
 * Notes:
 * - Never log returned values (may contain secrets).
 */
fun prop(name: String, default: String = ""): String {
    val fromGradle = providers.gradleProperty(name).orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (fromGradle != null) return fromGradle

    val fromGradleLocal = gradleLocalProps.getProperty(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (fromGradleLocal != null) return fromGradleLocal

    val fromLocal = localProps.getProperty(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (fromLocal != null) return fromLocal

    return default
}

/**
 * Read the first non-blank property value from multiple keys.
 *
 * Notes:
 * - Useful during migration (e.g., github.* -> gh.*).
 */
fun propAny(vararg names: String, default: String = ""): String {
    for (n in names) {
        val v = prop(n).trim()
        if (v.isNotEmpty()) return v
    }
    return default
}

/**
 * Escape a string literal for BuildConfig fields.
 *
 * BuildConfig fields expect a Java literal string, not a Kotlin string.
 */
fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Sanitize versionName for Android + Git tags:
 * - Must not contain spaces
 * - Keep it short and predictable for log aggregation
 */
fun sanitizeVersionName(raw: String): String =
    raw.trim()
        .replace("\\s+".toRegex(), "")
        .take(64)

/**
 * Resolve versionName with explicit precedence:
 *  1) -Papp.versionName=...
 *  2) env CI_APP_VERSION_NAME
 *  3) local override files (via prop)
 *  4) fallback
 */
fun resolveVersionName(): String {
    val fromGradle = providers.gradleProperty("app.versionName").orNull?.trim()
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
 *  3) env GITHUB_RUN_NUMBER
 *  4) fallback
 */
fun resolveVersionCode(): Int {
    val fromGradle = providers.gradleProperty("app.versionCode").orNull?.toIntOrNull()
    val fromEnv = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val fromRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
    return fromGradle ?: fromEnv ?: fromRunNumber ?: 1
}

/* ============================================================================
 * Setup tasks (Submodule / Model download)
 * ========================================================================== */

tasks.register<Exec>("checkSubmodule") {
    description = "Recursively initialize the native submodule if not yet set up"
    group = "setup"

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

tasks.register<Exec>("downloadModel") {
    description = "Run the model download script safely"
    group = "setup"

    val scriptInModule = file("download_models.sh")
    val scriptInRoot = rootProject.file("download_models.sh")
    val script = when {
        scriptInModule.exists() -> scriptInModule
        scriptInRoot.exists() -> scriptInRoot
        else -> scriptInModule
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

        val hfToken = propAny("hf.token", "HF_TOKEN").trim()
        if (hfToken.isNotBlank()) {
            environment("HF_TOKEN", hfToken)
        }
    }

    workingDir = script.parentFile
    commandLine("bash", script.absolutePath)

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

tasks.named("preBuild").configure {
    dependsOn("checkSubmodule", "downloadModel")
}

/* ============================================================================
 * Kotlin config (Built-in Kotlin) — keep at top-level
 * ========================================================================== */

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

/* ============================================================================
 * Android config (AGP 9+ public DSL)
 * ========================================================================== */

extensions.configure<ApplicationExtension> {
    val appId = prop("appId", "com.negi.survey")

    // ---- GitHub config (supports both github.* and legacy gh.*) ----
    val ghOwner = propAny("github.owner", "gh.owner")
    val ghRepo = propAny("github.repo", "gh.repo", default = "SurveyExports")
    val ghBranch = propAny("github.branch", "gh.branch", default = "main")
    val ghPathPrefix = propAny("github.pathPrefix", "gh.pathPrefix", default = "")
    val ghToken = propAny("github.token", "gh.token")
    val hfToken = propAny("hf.token", "HF_TOKEN")

    // ---- Supabase config (optional) ----
    val supabaseUrl = prop("supabase.url")
    val supabaseAnonKey = prop("supabase.anonKey")
    val supabaseLogBucket = prop("supabase.logBucket", "logs")
    val supabaseLogPrefix = prop("supabase.logPrefix", "surveyapp")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36

        val resolvedVersionName = resolveVersionName()
        val resolvedVersionCode = resolveVersionCode()
        versionName = resolvedVersionName
        versionCode = resolvedVersionCode

        val displayVersion = "$resolvedVersionName with WhisperCpp"
        buildConfigField("String", "DISPLAY_VERSION", quote(displayVersion))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        testInstrumentationRunnerArguments["numShards"] = "1"
    }

    testBuildType = "debug"

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            /** Avoid applicationIdSuffix to keep MediaStore ownership stable. */
            buildConfigField("String", "GH_OWNER", quote(ghOwner))
            buildConfigField("String", "GH_REPO", quote(ghRepo))
            buildConfigField("String", "GH_BRANCH", quote(ghBranch))
            buildConfigField("String", "GH_PATH_PREFIX", quote(ghPathPrefix))
            buildConfigField("String", "GH_TOKEN", quote(ghToken))
            buildConfigField("String", "HF_TOKEN", quote(hfToken))

            // Supabase (optional)
            buildConfigField("String", "SUPABASE_URL", quote(supabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", quote(supabaseAnonKey))
            buildConfigField("String", "SUPABASE_LOG_BUCKET", quote(supabaseLogBucket))
            buildConfigField("String", "SUPABASE_LOG_PATH_PREFIX", quote(supabaseLogPrefix))
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            buildConfigField("String", "GH_OWNER", quote(ghOwner))
            buildConfigField("String", "GH_REPO", quote(ghRepo))
            buildConfigField("String", "GH_BRANCH", quote(ghBranch))
            buildConfigField("String", "GH_PATH_PREFIX", quote(ghPathPrefix))
            buildConfigField("String", "GH_TOKEN", quote(ghToken))
            buildConfigField("String", "HF_TOKEN", quote(hfToken))

            // Supabase (optional)
            buildConfigField("String", "SUPABASE_URL", quote(supabaseUrl))
            buildConfigField("String", "SUPABASE_ANON_KEY", quote(supabaseAnonKey))
            buildConfigField("String", "SUPABASE_LOG_BUCKET", quote(supabaseLogBucket))
            buildConfigField("String", "SUPABASE_LOG_PATH_PREFIX", quote(supabaseLogPrefix))

            /**
             * Debug signing for CI/dev convenience.
             * For production distribution, switch to a proper release keystore.
             */
            signingConfig = signingConfigs.getByName("debug")
        }
    }

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

tasks.register("printAndroidTestArgs") {
    group = "verification"
    description = "Print resolved default instrumentation runner arguments."
    doLast {
        println("=== Default Instrumentation Args ===")
        val androidExt = project.extensions.getByType<ApplicationExtension>()
        val args = androidExt.defaultConfig.testInstrumentationRunnerArguments
        args.forEach { (k, v) -> println(" - $k = $v") }
        println("===================================")
        println("Override example: -Pandroid.testInstrumentationRunnerArguments.numShards=2")
    }
}

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
            .drop(1)
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
