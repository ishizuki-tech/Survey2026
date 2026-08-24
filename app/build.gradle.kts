// file: app/build.gradle.kts
import com.android.build.api.dsl.ApplicationExtension
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)

    // AGP 9+ uses built-in Kotlin for Android projects.
    // Do not apply org.jetbrains.kotlin.android here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/* ============================================================================
 * Shared helpers
 * ========================================================================== */

/** True when running under CI. */
val isCi: Boolean =
    System.getenv("CI")?.equals("true", ignoreCase = true) == true

/** Load optional repository-local Gradle properties. */
val gradleLocalProps: Properties = Properties().apply {
    val file = rootProject.file("gradle.properties.local")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/** Load Android local.properties. */
val localProps: Properties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/**
 * Resolve a property in this order:
 * 1. Gradle property
 * 2. gradle.properties.local
 * 3. local.properties
 * 4. default
 */
fun prop(name: String, default: String = ""): String {
    providers.gradleProperty(name).orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    gradleLocalProps.getProperty(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    localProps.getProperty(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }

    return default
}

/** Return the first non-blank property from the supplied names. */
fun propAny(vararg names: String, default: String = ""): String {
    for (name in names) {
        val value = prop(name).trim()
        if (value.isNotEmpty()) return value
    }
    return default
}

/**
 * Resolve from Gradle/local properties first, then environment variables.
 *
 * Do not log the returned value because it may contain a secret.
 */
fun propOrEnv(
    propertyNames: List<String>,
    environmentNames: List<String>,
    default: String = "",
): String {
    for (name in propertyNames) {
        val value = prop(name).trim()
        if (value.isNotEmpty()) return value
    }

    for (name in environmentNames) {
        val value = System.getenv(name)?.trim().orEmpty()
        if (value.isNotEmpty()) return value
    }

    return default
}

/** Escape a Java string literal used by BuildConfig. */
fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Keep versionName predictable and Android-safe. */
fun sanitizeVersionName(raw: String): String =
    raw.trim()
        .replace("\\s+".toRegex(), "")
        .take(64)

/** Resolve versionName from Gradle property, CI environment, or fallback. */
fun resolveVersionName(): String {
    val fromGradle =
        providers.gradleProperty("app.versionName").orNull?.trim()
    val fromEnv =
        System.getenv("CI_APP_VERSION_NAME")?.trim()
    val fromLocal =
        prop("app.versionName").trim()

    val raw = when {
        !fromGradle.isNullOrBlank() -> fromGradle
        !fromEnv.isNullOrBlank() -> fromEnv
        fromLocal.isNotBlank() -> fromLocal
        else -> "0.0.1"
    }

    return sanitizeVersionName(raw)
}

/** Resolve versionCode from Gradle property, CI environment, or fallback. */
fun resolveVersionCode(): Int {
    val fromGradle =
        providers.gradleProperty("app.versionCode").orNull?.toIntOrNull()
    val fromEnv =
        System.getenv("CI_VERSION_CODE")?.toIntOrNull()
    val fromRunNumber =
        System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

    return fromGradle ?: fromEnv ?: fromRunNumber ?: 1
}

/* ============================================================================
 * Setup tasks
 * ========================================================================== */

/** Parse submodule paths from .gitmodules. */
fun parseGitmodulesSubmodulePaths(gitmodules: File): List<String> {
    if (!gitmodules.exists()) return emptyList()

    val pathPattern = Regex("^path\\s*=\\s*(.+)$")

    return gitmodules.readLines()
        .mapNotNull { line ->
            pathPattern.find(line.trim())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
        }
        .filter { it.isNotBlank() }
        .distinct()
}

/** True when a submodule directory exists and contains files. */
fun looksInitialized(dir: File): Boolean =
    dir.exists() &&
            dir.isDirectory &&
            (dir.listFiles()?.isNotEmpty() == true)

tasks.register<Exec>("checkSubmodule") {
    description = "Initialize missing Git submodules recursively"
    group = "setup"

    val gitmodules = rootProject.file(".gitmodules")

    // Prefer .gitmodules as the source of truth.
    val candidatePaths =
        parseGitmodulesSubmodulePaths(gitmodules)
            .ifEmpty { listOf("whisper.cpp") }

    fun missingPaths(): List<String> =
        candidatePaths.filterNot { path ->
            looksInitialized(rootProject.file(path))
        }

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    onlyIf {
        val missing = missingPaths()

        if (missing.isNotEmpty()) {
            logger.lifecycle(
                "Submodules missing -> running git submodule update --init --recursive"
            )
            logger.lifecycle(
                "Missing: ${missing.joinToString(", ")}"
            )
        }

        missing.isNotEmpty()
    }

    workingDir = rootProject.projectDir

    commandLine(
        "git",
        "submodule",
        "update",
        "--init",
        "--recursive",
    )

    environment("GIT_TERMINAL_PROMPT", "0")

    isIgnoreExitValue = true
    standardOutput = stdout
    errorOutput = stderr

    doLast {
        val outText = stdout.toString().trim()
        val errText = stderr.toString().trim()

        if (outText.isNotEmpty()) {
            logger.lifecycle(outText)
        }

        if (errText.isNotEmpty()) {
            logger.warn(errText)
        }

        val exitCode =
            executionResult.orNull?.exitValue ?: 0

        if (exitCode != 0) {
            val message =
                "Submodule initialization failed (exit=$exitCode)."

            if (isCi) {
                throw GradleException(
                    "$message See logs above."
                )
            }

            logger.warn(
                "$message Continuing locally."
            )
            return@doLast
        }

        val remaining = missingPaths()

        if (remaining.isNotEmpty()) {
            val message =
                "Submodule initialization completed, but some submodules are still missing: " +
                        remaining.joinToString(", ")

            if (isCi) {
                throw GradleException(message)
            }

            logger.warn(message)
        } else {
            logger.lifecycle(
                "Submodule check completed."
            )
        }
    }
}

tasks.register<Exec>("downloadModel") {
    description = "Download required local model files"
    group = "setup"

    val scriptInModule = file("download_models.sh")
    val scriptInRoot = rootProject.file("download_models.sh")

    val script = when {
        scriptInModule.exists() -> scriptInModule
        scriptInRoot.exists() -> scriptInRoot
        else -> scriptInModule
    }

    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()

    val skipByProperty =
        prop("skipModelDownload", "false")
            .equals("true", ignoreCase = true)

    val skipByEnvironment =
        System.getenv("SKIP_MODEL_DOWNLOAD")
            ?.trim() == "1"

    onlyIf {
        when {
            skipByProperty || skipByEnvironment -> {
                logger.lifecycle(
                    "Model download skipped."
                )
                false
            }

            !script.exists() -> {
                logger.warn(
                    "download_models.sh not found in app/ or repository root. " +
                            "Skipping model download."
                )
                false
            }

            else -> true
        }
    }

    doFirst {
        if (!script.canExecute()) {
            script.setExecutable(true)
        }

        val hfToken = propOrEnv(
            propertyNames = listOf("hf.token"),
            environmentNames = listOf("HF_TOKEN"),
        )

        if (hfToken.isNotBlank()) {
            environment(
                "HF_TOKEN",
                hfToken,
            )
        }
    }

    workingDir = script.parentFile
    commandLine(
        "bash",
        script.absolutePath,
    )

    isIgnoreExitValue = true
    standardOutput = stdout
    errorOutput = stderr

    doLast {
        val outText = stdout.toString().trim()
        val errText = stderr.toString().trim()

        if (outText.isNotEmpty()) {
            logger.lifecycle(outText)
        }

        if (errText.isNotEmpty()) {
            logger.warn(errText)
        }

        val exitCode =
            executionResult.orNull?.exitValue ?: 0

        if (exitCode != 0) {
            throw GradleException(
                "Model download failed (exit=$exitCode). See logs above."
            )
        }

        logger.lifecycle(
            "Model download task finished."
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(
        "checkSubmodule",
        "downloadModel",
    )
}

/* ============================================================================
 * Kotlin
 * ========================================================================== */

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/* ============================================================================
 * Android
 * ========================================================================== */

extensions.configure<ApplicationExtension> {
    val appId =
        prop(
            "appId",
            "com.negi.survey",
        )

    /* ------------------------------------------------------------------------
     * GitHub configuration
     * ---------------------------------------------------------------------- */

    val ghOwner = propOrEnv(
        propertyNames =
            listOf(
                "github.owner",
                "gh.owner",
            ),
        environmentNames =
            listOf("GH_OWNER"),
    )

    val ghRepo = propOrEnv(
        propertyNames =
            listOf(
                "github.repo",
                "gh.repo",
            ),
        environmentNames =
            listOf("GH_REPO"),
        default = "SurveyExports",
    )

    val ghBranch = propOrEnv(
        propertyNames =
            listOf(
                "github.branch",
                "gh.branch",
            ),
        environmentNames =
            listOf("GH_BRANCH"),
        default = "main",
    )

    val ghPathPrefix = propOrEnv(
        propertyNames =
            listOf(
                "github.pathPrefix",
                "gh.pathPrefix",
            ),
        environmentNames =
            listOf("GH_PATH_PREFIX"),
    )

    val ghToken = propOrEnv(
        propertyNames =
            listOf(
                "github.token",
                "gh.token",
            ),
        environmentNames =
            listOf("GH_TOKEN"),
    )

    /* ------------------------------------------------------------------------
     * Hugging Face configuration
     * ---------------------------------------------------------------------- */

    val hfToken = propOrEnv(
        propertyNames =
            listOf("hf.token"),
        environmentNames =
            listOf("HF_TOKEN"),
    )

    /* ------------------------------------------------------------------------
     * Supabase configuration
     * ---------------------------------------------------------------------- */

    val supabaseUrl = propOrEnv(
        propertyNames =
            listOf("supabase.url"),
        environmentNames =
            listOf("SUPABASE_URL"),
    )

    val supabaseAnonKey = propOrEnv(
        propertyNames =
            listOf("supabase.anonKey"),
        environmentNames =
            listOf("SUPABASE_ANON_KEY"),
    )

    val supabaseLogBucket = propOrEnv(
        propertyNames =
            listOf("supabase.logBucket"),
        environmentNames =
            listOf("SUPABASE_LOG_BUCKET"),
        default = "logs",
    )

    val supabaseLogPrefix = propOrEnv(
        propertyNames =
            listOf("supabase.logPrefix"),
        environmentNames =
            listOf("SUPABASE_LOG_PREFIX"),
        default = "surveyapp",
    )

    /* ------------------------------------------------------------------------
     * Secret embedding policy
     * ---------------------------------------------------------------------- */

    // Debug builds may embed development credentials when explicitly enabled.
    val embedDebugSecrets =
        prop(
            "debug.embedSecrets",
            "true",
        ).equals(
            "true",
            ignoreCase = true,
        )

    // Internal release builds may embed the HF token only when explicitly enabled.
    //
    // GitHub credentials remain excluded from release builds.
    val allowReleaseSecrets =
        prop(
            "release.allowSecrets",
            "false",
        ).equals(
            "true",
            ignoreCase = true,
        )

    namespace = appId
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36

        val resolvedVersionName =
            resolveVersionName()

        val resolvedVersionCode =
            resolveVersionCode()

        versionName = resolvedVersionName
        versionCode = resolvedVersionCode

        buildConfigField(
            "String",
            "DISPLAY_VERSION",
            quote(
                "$resolvedVersionName with WhisperCpp"
            ),
        )

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        testInstrumentationRunnerArguments[
            "clearPackageData"
        ] = "true"

        testInstrumentationRunnerArguments[
            "useTestStorageService"
        ] = "true"

        testInstrumentationRunnerArguments[
            "numShards"
        ] = "1"
    }

    testBuildType = "debug"

    testOptions {
        execution =
            "ANDROIDX_TEST_ORCHESTRATOR"

        animationsDisabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.VERSION_17

        targetCompatibility =
            JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // Keep the applicationId stable so MediaStore ownership remains stable.

            buildConfigField(
                "String",
                "GH_OWNER",
                quote(ghOwner),
            )

            buildConfigField(
                "String",
                "GH_REPO",
                quote(ghRepo),
            )

            buildConfigField(
                "String",
                "GH_BRANCH",
                quote(ghBranch),
            )

            buildConfigField(
                "String",
                "GH_PATH_PREFIX",
                quote(ghPathPrefix),
            )

            buildConfigField(
                "String",
                "GH_TOKEN",
                quote(
                    if (embedDebugSecrets) {
                        ghToken
                    } else {
                        ""
                    }
                ),
            )

            buildConfigField(
                "String",
                "HF_TOKEN",
                quote(
                    if (embedDebugSecrets) {
                        hfToken
                    } else {
                        ""
                    }
                ),
            )

            buildConfigField(
                "String",
                "SUPABASE_URL",
                quote(supabaseUrl),
            )

            buildConfigField(
                "String",
                "SUPABASE_ANON_KEY",
                quote(supabaseAnonKey),
            )

            buildConfigField(
                "String",
                "SUPABASE_LOG_BUCKET",
                quote(supabaseLogBucket),
            )

            buildConfigField(
                "String",
                "SUPABASE_LOG_PATH_PREFIX",
                quote(supabaseLogPrefix),
            )
        }

        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro",
            )

            buildConfigField(
                "String",
                "GH_OWNER",
                quote(ghOwner),
            )

            buildConfigField(
                "String",
                "GH_REPO",
                quote(ghRepo),
            )

            buildConfigField(
                "String",
                "GH_BRANCH",
                quote(ghBranch),
            )

            buildConfigField(
                "String",
                "GH_PATH_PREFIX",
                quote(ghPathPrefix),
            )

            // Never embed GitHub credentials in release artifacts.
            buildConfigField(
                "String",
                "GH_TOKEN",
                quote(""),
            )

            // Allow the HF token only for explicitly enabled internal releases.
            buildConfigField(
                "String",
                "HF_TOKEN",
                quote(
                    if (allowReleaseSecrets) {
                        hfToken
                    } else {
                        ""
                    }
                ),
            )

            buildConfigField(
                "String",
                "SUPABASE_URL",
                quote(supabaseUrl),
            )

            buildConfigField(
                "String",
                "SUPABASE_ANON_KEY",
                quote(supabaseAnonKey),
            )

            buildConfigField(
                "String",
                "SUPABASE_LOG_BUCKET",
                quote(supabaseLogBucket),
            )

            buildConfigField(
                "String",
                "SUPABASE_LOG_PATH_PREFIX",
                quote(supabaseLogPrefix),
            )

            // Debug signing is available only as an explicit local/CI opt-in.
            if (
                prop(
                    "release.useDebugSigning",
                    "false",
                ).equals(
                    "true",
                    ignoreCase = true,
                )
            ) {
                signingConfig =
                    signingConfigs.getByName("debug")
            }
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
    implementation(
        project(":nativelib")
    )

    // Compose BOM.
    implementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    // Compose.
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime.saveable)

    // Debug/preview.
    debugImplementation(
        libs.androidx.ui.tooling
    )

    debugImplementation(
        libs.androidx.ui.test.manifest
    )

    // Navigation.
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.accompanist.navigation.animation)

    // Kotlin, coroutines, serialization.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kaml)

    // AndroidX core.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)

    // Lifecycle.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(
        libs.androidx.lifecycle.viewmodel.navigation3.android
    )
    implementation(libs.androidx.lifecycle.process)

    // Persistence.
    implementation(libs.androidx.room.ktx)

    // WorkManager.
    implementation(libs.androidx.work.runtime.ktx)

    // Networking.
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Security.
    implementation(libs.androidx.security.crypto)

    // Media.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // On-device SLM: LiteRT-LM only.
    implementation(libs.litertlm)

    // Test utilities.
    androidTestImplementation(
        libs.androidx.documentfile
    )

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.androidx.test.runner)

    androidTestUtil(
        libs.androidx.test.orchestrator
    )
}

/* ============================================================================
 * Diagnostic tasks
 * ========================================================================== */

tasks.register("printAndroidTestArgs") {
    group = "verification"

    description =
        "Print resolved default instrumentation runner arguments."

    doLast {
        println(
            "=== Default Instrumentation Args ==="
        )

        val androidExt =
            project.extensions
                .getByType<ApplicationExtension>()

        val args =
            androidExt.defaultConfig
                .testInstrumentationRunnerArguments

        args.forEach { (key, value) ->
            println(
                " - $key = $value"
            )
        }

        println(
            "==================================="
        )

        println(
            "Override example: " +
                    "-Pandroid.testInstrumentationRunnerArguments.numShards=2"
        )
    }
}

tasks.register("checkSingleConnectedDevice") {
    group = "verification"

    description =
        "Fail when more than one Android device/emulator is connected."

    doLast {
        val adbCheck =
            ProcessBuilder(
                "bash",
                "-lc",
                "command -v adb >/dev/null 2>&1",
            ).start()

        adbCheck.waitFor()

        if (adbCheck.exitValue() != 0) {
            throw GradleException(
                "adb is not available on PATH. " +
                        "Install Android platform-tools or add adb to PATH."
            )
        }

        val process =
            ProcessBuilder(
                "adb",
                "devices",
            )
                .redirectErrorStream(true)
                .start()

        val output =
            process.inputStream
                .bufferedReader()
                .readText()

        process.waitFor()

        val devices =
            output.lineSequence()
                .drop(1)
                .map { it.trim() }
                .filter {
                    it.isNotEmpty() &&
                            it.contains("\tdevice")
                }
                .toList()

        println(
            "Connected devices: ${devices.size}"
        )

        devices.forEach {
            println(
                " - $it"
            )
        }

        if (devices.size > 1) {
            throw GradleException(
                "More than one device/emulator is connected. " +
                        "Keep exactly one to avoid duplicate test runs."
            )
        }
    }
}

tasks.register("printAssets") {
    group = "diagnostic"

    description =
        "Print all assets included in app/src/main/assets."

    doLast {
        val assetsDir =
            file("src/main/assets")

        if (!assetsDir.exists()) {
            println(
                "No assets directory found."
            )
            return@doLast
        }

        val assetFiles =
            assetsDir.walkTopDown()
                .filter { it.isFile }
                .toList()

        if (assetFiles.isEmpty()) {
            println(
                "Assets directory is empty."
            )
        } else {
            println(
                "Found ${assetFiles.size} asset files under: " +
                        assetsDir.absolutePath
            )

            assetFiles.forEach { assetFile ->
                println(
                    " - ${assetFile.relativeTo(assetsDir)} " +
                            "(${assetFile.length()} bytes)"
                )
            }
        }
    }
}
android {
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
