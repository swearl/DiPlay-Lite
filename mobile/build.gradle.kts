plugins {
    alias(libs.plugins.android.application)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

// versionCode = DiPlay-Lite commit count + 1 (workspace AGENTS.md rule).
// The repo must have at least one commit before building.
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "tech.touka.diplay.lite"
        minSdk = 19
        targetSdk = 19
        // versionName follows the upstream mobile module (0.3.12).
        versionCode = commitCount + 1
        versionName = "0.3.12"
        ndk {
            // Workspace AGENTS.md rule: package armeabi-v7a native libs only
            // (also filters jniLibs pulled in by AAR dependencies like conscrypt).
            abiFilters += "armeabi-v7a"
        }
    }

    signingConfigs {
        create("release") {
            // Credentials come from the environment only; no hardcoded fallback so
            // secrets are never committed (see workspace AGENTS.md).
            storeFile = file(requireNotNull(System.getenv("ANDROID_KEYSTORE_PATH")) {
                "ANDROID_KEYSTORE_PATH is required"
            })
            storePassword = requireNotNull(System.getenv("ANDROID_KEYSTORE_PASSWORD")) {
                "ANDROID_KEYSTORE_PASSWORD is required"
            }
            keyAlias = requireNotNull(System.getenv("ANDROID_KEY_ALIAS")) {
                "ANDROID_KEY_ALIAS is required"
            }
            keyPassword = requireNotNull(System.getenv("ANDROID_KEY_PASSWORD")) {
                "ANDROID_KEY_PASSWORD is required"
            }
        }
    }

    buildTypes {
        debug {
            // The head unit only installs system-signed packages; debug builds must
            // carry the same Freescale signature as release.
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))
}
