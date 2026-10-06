plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// "Phone link": turns a rooted Android 13 player into a Bluetooth hands-free unit for a simple phone
// (calls, contacts and call history over HFP / PBAP, like a car's media system).
// It must run as a privileged system app, so it ships as a Magisk module (see the magisk/ folder).

val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.offline.phonelink"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offline.phonelink"
        minSdk = 31
        targetSdk = 34
        versionCode = ciRunNumber
        versionName = "1.0.$ciRunNumber"
        resourceConfigurations += listOf("iw")
    }

    signingConfigs {
        // A fixed key committed with the project, so every build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // The Bluetooth hands-free API is reached by reflection: keep the code as written.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    // Lets the app call Android's hidden Bluetooth hands-free (HFP client) API.
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}

// ------------------------------------------------------------------ Magisk module
// build/outputs/magisk/PhoneLink-magisk.zip: installs the APK as a privileged system app, whitelists its
// privileged permissions and turns on the player's hands-free / phonebook Bluetooth profiles.
val packageMagiskModule by tasks.registering(Zip::class) {
    dependsOn("assembleRelease")
    archiveFileName.set("PhoneLink-magisk.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/magisk"))
    from("magisk")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "PhoneLink.apk" }
        into("system/priv-app/PhoneLink")
    }
}
