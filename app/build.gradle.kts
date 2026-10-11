import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing key, kept out of git in signing/ (see signing/signing.properties).
val signingProps = Properties().apply {
    val file = rootProject.file("signing/signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.mapstyleeditor"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.minmap"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.1"
    }

    // "phone" is MinMap as published: Google Maps does the navigating, MinMap draws it. "aosp" is for
    // Android without Google (car head units, de-Googled phones): no Google Maps, no Play services,
    // MinMap guides by itself, and the token can be sent over from a phone by QR code.
    flavorDimensions += "platform"
    productFlavors {
        create("phone") {
            dimension = "platform"
            isDefault = true
            buildConfigField("boolean", "AOSP", "false")
        }
        create("aosp") {
            dimension = "platform"
            applicationIdSuffix = ".aosp"
            // Released on its own (GitHub pre-releases), so it has its own version.
            versionCode = 10
            versionName = "1.4.2"
            buildConfigField("boolean", "AOSP", "true")
        }
    }

    // Also build single-CPU APKs next to the universal one. Mapbox's native engine is compiled per
    // CPU type, so a single-ABI APK is a fraction of the size and easier to send. Phones are arm64;
    // many Android 9 head units run 32-bit ARM (armeabi-v7a), even on 64-bit chips.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // Compress native libraries inside the APK (they're unpacked once at install). Roughly halves
    // the download, which keeps the phone APK under chat attachment limits.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The aosp build carries no Google Play services at all. Mapbox's core pulls in Play services'
// Cronet for its networking; the aosp manifest switches Mapbox to its built-in OkHttp client
// instead, and Mapbox finds location through Android's own LocationManager when Play services
// isn't there, so the whole group is left out.
configurations.configureEach {
    if (name.startsWith("aosp")) exclude(group = "com.google.android.gms")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.mapbox.maps)
    implementation(libs.mapbox.compose)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    // QR code for sending the Mapbox token from a phone (aosp sign-in). Plain Java, no Play services.
    implementation(libs.zxing.core)
    // Offline speech recognition for "navigate to..." on devices with no recognizer (aosp only; the
    // English model is downloaded on first use, not shipped). JNA must be the Android (aar) build.
    "aospImplementation"(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    "aospImplementation"(variantOf(libs.jna) { artifactType("aar") })

    testImplementation(libs.junit)
    // Android's org.json is only a stub in local unit tests; the real one parses the Directions JSON.
    testImplementation(libs.json)
}
