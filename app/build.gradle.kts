plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.orbit"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.stormyy14.orbitline"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.7.1"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never from the repo).
    // Without those properties, release builds fall back to the debug key so anyone can build.
    val storeFilePath = providers.gradleProperty("ORBIT_STORE_FILE").orNull
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("ORBIT_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ORBIT_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ORBIT_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    dependenciesInfo {
        // No Google-encrypted dependency blob in the APK (keeps builds reproducible / F-Droid friendly).
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Tor's native library: compressed in the APK (a much smaller download) and skipped
            // for 32-bit x86, which no current phone uses.
            useLegacyPackaging = true
            excludes += "lib/x86/**"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    // Orbit VPN: the Tor client (BSD-3-Clause), run in-process. Its Kotlin stdlib would be newer
    // than the app's compiler; the library's code is Java and doesn't need it.
    implementation("info.guardianproject:tor-android:0.4.9.13") { exclude(group = "org.jetbrains.kotlin") }
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    testImplementation("junit:junit:4.13.2")
}

// tor-android declares minCompileSdk 37.1 only because of how it's built: its classes use nothing
// newer than API 24 (Service, Intent, FileObserver; checked with javap). AGP 8.11 can't compile
// against 37.1, so that one metadata check is skipped. Re-enable it when the project moves to 37.
tasks.matching { it.name.matches(Regex("check.*AarMetadata")) }.configureEach { enabled = false }
