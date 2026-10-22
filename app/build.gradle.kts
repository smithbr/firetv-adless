import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Releases are signed with a key kept outside the repo, so each release, and
// each build made with the same key, updates the installed app in place. The
// keystore's path and password come from Gradle properties (for example in
// ~/.gradle/gradle.properties) or the environment (CI). Without them, as in a
// fresh clone, the release is signed with the debug key and cannot update an
// installed release.
val releaseKeystore = providers.gradleProperty("launcher.keystore")
    .orElse(providers.environmentVariable("LAUNCHER_KEYSTORE"))
    .orNull
    ?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = providers.gradleProperty("launcher.keystorePassword")
    .orElse(providers.environmentVariable("LAUNCHER_KEYSTORE_PASSWORD"))
    .orNull
    ?.takeIf { it.isNotBlank() }

android {
    namespace = "la.bsmith.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "la.bsmith.launcher"
        // Fire OS 8 is Android 11 (API 30); Fire OS 6 is API 25.
        minSdk = 25
        targetSdk = 30
        versionCode = 5
        versionName = "0.5"
        resValue("string", "app_name", "Launcher")
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = "launcher"
                // A PKCS12 keystore has one password for the store and its key.
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // The debug variant is a separate app, so it can sit beside the
        // installed release as a throwaway test app (uninstall, for one).
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Launcher Debug")
        }
        // For tests/device: built like the release, so frame timings match,
        // but a separate app the harness can clear, with intent extras that
        // set the theme and freeze the clock (src/devicetest).
        create("devicetest") {
            initWith(getByName("release"))
            // The debug key, so device tests never need the release key.
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".devicetest"
            resValue("string", "app_name", "Launcher Test")
            matchingFallbacks += "release"
        }
    }

    // The no-op DeviceTest the shipped variants compile against.
    sourceSets {
        getByName("release").kotlin.directories.add("src/shipping/java")
        getByName("debug").kotlin.directories.add("src/shipping/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Each build type sets its own app_name; AGP 9 turns this off by default.
        resValues = true
    }

    lint {
        // A Play Store policy; this app is sideloaded and targets the TV's own API level.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
}
