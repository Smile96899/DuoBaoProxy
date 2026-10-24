plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.duobao.proxy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.duobao.proxy"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // Release ships arm64 only (DESIGN §0). `-Pdbp.releaseAbis=x86_64` builds a release APK for the
    // emulator to check that R8 keeps the gomobile bindings intact; the AAR must contain that ABI.
    val releaseAbis = providers.gradleProperty("dbp.releaseAbis").orNull?.split(',')?.map(String::trim) ?: listOf("arm64-v8a")

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { abiFilters += releaseAbis }
            // TODO(release): replace with the real release key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // gomobile bind output of go/mobile (go/build_mobile.sh); package com.duobao.core.mobile.
    implementation(files("libs/dbcore.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
