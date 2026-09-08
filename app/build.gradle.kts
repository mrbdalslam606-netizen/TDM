plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.tdm.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tdm.app"
        minSdk = 31          // Android 12 — the primary target device (Samsung Galaxy A31)
        targetSdk = 31       // Android 12-first behavior: classic FGS rules, exact alarms allowed by default
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val signingStore = providers.gradleProperty("RELEASE_STORE_FILE").orNull
                ?: System.getenv("RELEASE_STORE_FILE")
            val signingStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val signingKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val signingKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
                ?: System.getenv("RELEASE_KEY_PASSWORD")
            if (signingStore != null && signingStorePassword != null &&
                signingKeyAlias != null && signingKeyPassword != null) {
                storeFile = file(signingStore)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    splits { abi { isEnable = false } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // Optional per-ABI APK: ./gradlew :app:assembleRelease -PtdmAbi=arm64-v8a
    // (smaller download for limited bandwidth; omit the property for the universal APK)
    splits {
        abi {
            val only = (project.findProperty("tdmAbi") as String?)
            if (only != null) {
                isEnable = true
                reset()
                include(only)
                isUniversalApk = false
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Personal-use app, NOT distributed via Google Play (spec §69).
        // targetSdk 31 is intentional: Android 12-first behavior (classic FGS rules,
        // exact alarms granted by default, no FGS-type/POST_NOTIFICATIONS constraints).
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // TDLib — prebuilt AAR: org.drinkless.tdlib Java API + libtdjni.so + openssl natives
    // ABIs: arm64-v8a (Galaxy A31), armeabi-v7a, x86, x86_64
    implementation("com.github.capullo-tech:lib-tdlib-android:bc9a091")

    // Kotlin / coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // DataStore (settings key-value)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // DocumentFile for SAF tree handling
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Shizuku (optional background reliability enhancements)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Unit tests (pure JVM)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
