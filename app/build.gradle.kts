plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// All distributable variants must use the same persistent signing identity. Never
// fall back to Android's per-sandbox debug key: that breaks in-place updates.
val signingEnv = System.getenv()
val signingStoreFile = signingEnv["VOICE_REPS_KEYSTORE_FILE"]
val signingStorePassword = signingEnv["VOICE_REPS_STORE_PASSWORD"]
val signingKeyAlias = signingEnv["VOICE_REPS_KEY_ALIAS"]
val signingKeyPassword = signingEnv["VOICE_REPS_KEY_PASSWORD"]
val missingSigningValues = listOf(
    "VOICE_REPS_KEYSTORE_FILE" to signingStoreFile,
    "VOICE_REPS_STORE_PASSWORD" to signingStorePassword,
    "VOICE_REPS_KEY_ALIAS" to signingKeyAlias,
    "VOICE_REPS_KEY_PASSWORD" to signingKeyPassword,
).filter { it.second.isNullOrBlank() }.map { it.first }

if (missingSigningValues.isNotEmpty()) {
    throw GradleException(
        "Persistent voice-reps signing is not configured. Set the required environment " +
            "variables: ${missingSigningValues.joinToString(", ")}. See README.md -> Signing."
    )
}

android {
    namespace = "com.voicereps.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicereps.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "0.1.4"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("persistent") {
            storeFile = file(signingStoreFile!!)
            storePassword = signingStorePassword!!
            keyAlias = signingKeyAlias!!
            keyPassword = signingKeyPassword!!
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            signingConfig = signingConfigs.getByName("persistent")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "384m"; it.maxParallelForks = 1 }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
