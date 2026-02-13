// file: nativelib/build.gradle.kts
// ============================================================
// whisper.cpp JNI Library Module — AGP 9+ (Built-in Kotlin)
// ------------------------------------------------------------
// - AGP 9.x / Gradle 8.x / Kotlin (built-in) / NDK 29.0
// - Stable externalNativeBuild + GGML flags
// - CPU-only Android build
// ============================================================

plugins {
    id("com.android.library")
    // NOTE:
    // AGP 9+ provides built-in Kotlin. Do NOT apply org.jetbrains.kotlin.android here.
}

android {
    namespace = "com.whispercpp"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Only build for arm64 for Android devices
            //noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a")
        }

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                val ggmlHome = project.findProperty("GGML_HOME")?.toString()

                val args = mutableListOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_METAL=OFF",
                    "-DGGML_CUDA=OFF",
                    "-DGGML_OPENCL=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DWHISPER_EXTRA=OFF"
                )

                if (!ggmlHome.isNullOrBlank()) {
                    args += "-DGGML_HOME=$ggmlHome"
                }

                arguments.addAll(args)

                // -O2 is faster to build + stable
                cFlags.add("-O2")

                // Split flags to avoid being treated as a single token
                cppFlags.addAll(listOf("-O2", "-fexceptions", "-frtti"))
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "JNI_DEBUG", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "JNI_DEBUG", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // CMake Path (whisper.cpp JNI)
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Default Android sources already include src/main/java and src/main/kotlin under built-in Kotlin.
    // Keep sourceSets only if you truly need non-standard dirs.

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += listOf("**/libc++_shared.so")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// IMPORTANT:
// With AGP 9 built-in Kotlin, migrate android.kotlinOptions{} -> kotlin.compilerOptions{}.
// This block must be TOP-LEVEL (Project), not inside android{} or dependencies{}.
kotlin {
    compilerOptions {
        // NOTE:
        // With built-in Kotlin, jvmTarget defaults to android.compileOptions.targetCompatibility.
        freeCompilerArgs.addAll(
            listOf(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn"
            )
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
