import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes its run number so every published APK installs as an update over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.jakobholmelund.hypervip"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jakobholmelund.hypervip"
        minSdk = 31
        // Deliberately 34: apps targeting 35+ can no longer change the global Do Not Disturb
        // state, which the "Total silence" fallback needs. This app is sideloaded, so Play's
        // target SDK requirement doesn't apply.
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // A private key can be supplied through CI secrets; otherwise the key committed in
            // signing/ is used so updates install over each other (see README).
            storeFile = env("HYPERVIP_KEYSTORE_PATH")?.let { file(it) } ?: rootProject.file("signing/hyper-vip.jks")
            storePassword = env("HYPERVIP_KEYSTORE_PASSWORD") ?: "hypervip"
            keyAlias = env("HYPERVIP_KEY_ALIAS") ?: "hypervip"
            keyPassword = env("HYPERVIP_KEY_PASSWORD") ?: "hypervip"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // See the targetSdk comment above.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    testImplementation(libs.junit)
}
