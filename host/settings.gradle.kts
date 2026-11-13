pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // `npx expo-brownfield build:android` publishes the AAR here.
    mavenLocal()
  }
}

rootProject.name = "host"
include(":app")
