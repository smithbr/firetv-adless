import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "la.bsmith.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "la.bsmith.launcher"
        // Fire OS 8 is Android 11 (API 30); Fire OS 6 is API 25.
        minSdk = 25
        targetSdk = 30
        versionCode = 2
        versionName = "0.2"
        resValue("string", "app_name", "Launcher")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
