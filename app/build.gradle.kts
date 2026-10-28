plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** A value from the environment or a Gradle property; never stored in the repo. Quotes/backslashes are dropped. */
fun buildSecret(name: String): String =
    (System.getenv(name)?.takeIf { it.isNotBlank() } ?: (project.findProperty(name) as String?) ?: "")
        .trim().filter { it != '"' && it != '\\' && it != '$' }

android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    namespace = "tv.tvstreamer"
    compileSdk = 34
    defaultConfig {
        applicationId = "tv.tvstreamer"
        minSdk = 23
        targetSdk = 34
        versionCode = 46
        versionName = "1.46"
        // Built-in Google sign-in client (TV / limited-input type). Supplied only by the CI secrets
        // GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET (or the same-named env vars / Gradle properties locally);
        // empty when missing, and the app then asks for a client under Settings > Google account > Advanced.
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${buildSecret("GOOGLE_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${buildSecret("GOOGLE_CLIENT_SECRET")}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") { signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
