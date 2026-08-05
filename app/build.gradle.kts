plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  // alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.flowave.music"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
    val storePass = System.getenv("STORE_PASSWORD")
    val alias = System.getenv("KEY_ALIAS")
    val keyPass = System.getenv("KEY_PASSWORD")

    // A release build is intentionally reproducible without private signing secrets.
    // CI supplies these values for a signed artifact; otherwise release falls back to
    // the local debug keystore below instead of failing during configuration.
    val hasAnyReleaseSecret = releaseKeystorePath != null || storePass != null || alias != null || keyPass != null

    if (hasAnyReleaseSecret) {
      if (releaseKeystorePath == null) throw GradleException("Missing environment variable: KEYSTORE_PATH")
      if (storePass == null) throw GradleException("Missing environment variable: STORE_PASSWORD")
      if (alias == null) throw GradleException("Missing environment variable: KEY_ALIAS")
      if (keyPass == null) throw GradleException("Missing environment variable: KEY_PASSWORD")

      val releaseKeystoreFile = file(releaseKeystorePath)
      if (!releaseKeystoreFile.exists()) {
        throw GradleException("Keystore file does not exist at path: $releaseKeystorePath")
      }

      create("release") {
        storeFile = releaseKeystoreFile
        storePassword = storePass
        keyAlias = alias
        keyPassword = keyPass
      }
    }

    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseConfig = signingConfigs.findByName("release")
      signingConfig = releaseConfig ?: signingConfigs.getByName("debugConfig")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }

  // Native yt-dlp and FFmpeg binaries are large when all CPU architectures
  // are bundled into one universal APK. Ship one small APK per architecture;
  // every split retains the complete streaming and download feature set.
  splits {
    abi {
      isEnable = true
      reset()
      // FloWave targets physical Android phones; arm64 is the recommended
      // download and armeabi-v7a keeps compatibility with older phones.
      include("arm64-v8a", "armeabi-v7a")
      isUniversalApk = false
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
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)

  // Media3 ExoPlayer for audio playback & session
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.datasource)
  implementation(libs.androidx.media3.database)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.ui)
  implementation(libs.androidx.media3.common)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  // GPL-3.0 yt-dlp/FFmpeg Android integration, adapted from Seal's
  // production download lifecycle. See THIRD_PARTY_NOTICES.md.
  implementation(libs.youtubedl.android.library)
  implementation(libs.youtubedl.android.ffmpeg)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
