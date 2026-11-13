plugins {
  id("com.android.application")
}

android {
  namespace = "dev.repro.host"
  compileSdk = 37

  defaultConfig {
    applicationId = "dev.repro.host"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Debug keystore so the release build can be installed on the emulator.
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  // Release AAR built from ../rn with `npx expo-brownfield build:android --release`.
  implementation("dev.repro:brownfield:1.0.0")
  implementation("androidx.appcompat:appcompat:1.7.1")
  implementation("androidx.fragment:fragment-ktx:1.8.9")
}
