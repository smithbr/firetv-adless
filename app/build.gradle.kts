import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "la.bsmith.adless"
    compileSdk = 36

    defaultConfig {
        applicationId = "la.bsmith.adless"
        // Fire OS 8 is Android 11 (API 30); Fire OS 6 is API 25.
        minSdk = 25
        targetSdk = 30
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            resValue("string", "app_name", "Adless")
        }
        // The debug variant is a separate app, so it can sit beside the
        // installed release as a throwaway test app (uninstall, for one).
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Adless Debug")
        }
        // For tests/device: built like the release, so frame timings match,
        // but a separate app the harness can clear, with intent extras that
        // set the theme and freeze the clock (src/devicetest).
        create("devicetest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".devicetest"
            resValue("string", "app_name", "Adless Test")
            matchingFallbacks += "release"
        }
    }

    // The no-op DeviceTest the shipped variants compile against.
    sourceSets {
        getByName("release").java.srcDir("src/shipping/java")
        getByName("debug").java.srcDir("src/shipping/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
