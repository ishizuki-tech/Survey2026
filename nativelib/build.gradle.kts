// file: nativelib/build.gradle.kts
// ============================================================
// ✅ whisper.cpp JNI Library Module — Final Stable v2.0
// ------------------------------------------------------------
// • AGP 8.13 / Gradle 8.14 / Kotlin 2.2 / NDK 28.1
// • Stable externalNativeBuild + GGML flags
// • No GPU deps (CPU-only Android build)
// ============================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whispercpp"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // ✅ Only build for arm64 for Android devices
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

                // ✅ Correct Kotlin DSL call
                arguments.addAll(args)

                // ✅ O2 is faster to build + stable
                cFlags.add("-O2")
                cppFlags.add("-O2 -fexceptions -frtti")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    // ✅ CMake Path (whisper.cpp JNI)
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/whisper/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", "src/main/kotlin")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
