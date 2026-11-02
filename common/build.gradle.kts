plugins {
    id("com.android.library")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

tasks.configureEach {
    // AGP 9's lintVital crashes on JBR 17 (List.removeLast is a JDK 21 API).
    if (name.startsWith("lintVital")) {
        enabled = false
    }
}

dependencies {
    api(project(":shared"))
    // OkHttp 3.12 is the last API 19-compatible line. Keep the legacy updater off
    // Android 4.4's system HttpsURLConnection, which can fall back to SSLv3.
    implementation("com.squareup.okhttp3:okhttp-urlconnection:3.12.13")
    implementation("org.jmdns:jmdns:3.6.3")
}
