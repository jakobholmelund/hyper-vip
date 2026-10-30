import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

// The commit count only grows, so every build installs as an update over the previous one.
val buildNumber = env("HYPERVIP_VERSION_CODE")?.toIntOrNull()
    ?: runCatching {
        providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
            .standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)

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
        // Bump for each release; the version code (commit count) keeps updates installable.
        versionName = "1.1.0"
    }

    signingConfigs {
        // The release key is private and never in the repo; scripts/build-apk.sh provides it
        // from the environment (see README, "Signing"). Without it the release APK is unsigned.
        val keystore = env("HYPERVIP_KEYSTORE_PATH")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = env("HYPERVIP_KEYSTORE_PASSWORD")
                keyAlias = env("HYPERVIP_KEY_ALIAS") ?: "hypervip"
                keyPassword = env("HYPERVIP_KEY_PASSWORD") ?: env("HYPERVIP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
