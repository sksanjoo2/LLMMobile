import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing secrets live outside version control (see keystore.properties / .gitignore).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.gemmamobile.securechat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gemmamobile.securechat"
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL requires API 30+, and devices that can
        // run a 4B-parameter model on-device are Android 11+ anyway.
        minSdk = 30
        targetSdk = 36
        versionCode = 14
        versionName = "1.5.0"

        // LiteRT-LM ships arm64-v8a and x86_64; real phones only need arm64.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    flavorDimensions += "network"
    productFlavors {
        // No INTERNET permission at all: the OS guarantees nothing can leave the device.
        // The model file is imported from local storage.
        create("offline") {
            dimension = "network"
            buildConfigField("boolean", "ALLOW_DOWNLOAD", "false")
        }
        // Adds INTERNET (see src/online/AndroidManifest.xml) solely for the one-time,
        // hash-verified model download. Chats never touch the network.
        create("online") {
            dimension = "network"
            applicationIdSuffix = ".online"
            versionNameSuffix = "-online"
            buildConfigField("boolean", "ALLOW_DOWNLOAD", "true")
        }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
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

    packaging {
        // The model is loaded via mmap by the native runtime; keep .so files uncompressed/aligned.
        jniLibs { useLegacyPackaging = false }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")

    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.biometric:biometric:1.1.0")
}
