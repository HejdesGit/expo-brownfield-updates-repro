<!--
DRAFT, not posted. Target: new issue in expo/expo (bug report template).
Title:
[expo-updates][android] pickFirst inside testOptions overrides the AAR's excludes, so brownfield AARs ship their own libc++_shared.so / libfbjni.so
-->

### Summary

`packages/expo-updates/android/build.gradle` has two `pickFirst` entries inside `testOptions { packagingOptions { … } }`:

```groovy
testOptions {
  unitTests.includeAndroidResources = true

  packagingOptions {
    pickFirst "**/libfbjni.so"
    pickFirst "**/libc++_shared.so"
    …
  }
}
packagingOptions {
  excludes += [
    "**/libc++_shared.so",
    "**/libfbjni.so",
  ]
}
```

`testOptions` has no `packagingOptions` of its own, so Groovy resolves the inner block against `android {}` and the `pickFirst` entries land on the library's normal packaging options. There they win over the `excludes` added in #40091, and the published `expo.modules.updates` AAR contains its own `jni/<abi>/libc++_shared.so` and `jni/<abi>/libfbjni.so`. The same lines are on `main` today.

In a regular Expo app this rarely shows. In a brownfield setup (`expo-brownfield build:android`), the host app consumes the published AARs. Whenever expo-updates' copy of `libc++_shared.so` differs from the one in `react-android`, the host's `mergeReleaseNativeLibs` fails. The copy differs when the brownfield project builds native code with a different NDK than the React Native prebuilts, because expo-updates now honours `rootProject.ext.ndkVersion` (#45759).

### Reproduction

Repository: https://github.com/HejdesGit/expo-brownfield-updates-repro (see "Bug 2" in the README)

- `rn/`: `create-expo-app` (SDK 58) with `expo-updates` and `expo-brownfield`, built with `npx expo-brownfield build:android --release`
- `host/`: a minimal native Android app that depends on `dev.repro:brownfield:1.0.0` from `mavenLocal()`

1. `NDK_VERSION=27.0.12077973 ./scripts/build-aar.sh` (builds the AAR with an installed NDK other than React Native's default 27.1.12297006)
2. `cd host && ./gradlew assembleRelease`

### What happens

The `expo.modules.updates-58.0.22.aar` published to `~/.m2` contains:

```
jni/arm64-v8a/libc++_shared.so
jni/arm64-v8a/libexpo-updates.so
jni/arm64-v8a/libfbjni.so
```

and the host build fails:

```
Execution failed for task ':app:mergeReleaseNativeLibs'.
> A failure occurred while executing com.android.build.gradle.internal.tasks.MergeNativeLibsTask$MergeNativeLibsTaskWorkAction
   > 2 files found with path 'lib/arm64-v8a/libc++_shared.so' from inputs:
      - ~/.gradle/caches/9.4.1/transforms/…/transformed/expo.modules.updates-58.0.22/jni/arm64-v8a/libc++_shared.so
      - ~/.gradle/caches/9.4.1/transforms/…/transformed/react-android-0.88.1-release/jni/arm64-v8a/libc++_shared.so
```

With the default NDK (27.1.12297006) the host build succeeds: the AAR still contains both files, but they are byte-identical to the copies in `react-android` and `fbjni` (same MD5), so AGP merges them without an error. The extra copies are still there and break as soon as the NDKs differ.

### Expected

The AAR honours its own `excludes` and contains only `libexpo-updates.so`, like other Expo modules that link against `libc++_shared`/`fbjni`.

### Confirmation

Removing only the two `pickFirst` lines (`SO_FIX=1 NDK_VERSION=27.0.12077973 ./scripts/build-aar.sh`) gives an AAR with only `jni/arm64-v8a/libexpo-updates.so`, and `./gradlew assembleRelease` in `host/` succeeds. Full output for all four configurations: [`logs/bug2-summary.txt`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/logs/bug2-summary.txt).

### Possible fix

The `pickFirst` lines most likely exist for the module's instrumentation test APK, which with the `excludes` alone would get no copy of the two libraries. Scoping them to the androidTest component keeps that working without affecting the AAR:

```diff
--- a/packages/expo-updates/android/build.gradle
+++ b/packages/expo-updates/android/build.gradle
@@ -131,9 +131,6 @@
     unitTests.includeAndroidResources = true
 
     packagingOptions {
-      pickFirst "**/libfbjni.so"
-      pickFirst "**/libc++_shared.so"
-
       resources.excludes.add("META-INF/LICENSE.md")
       resources.excludes.add("META-INF/LICENSE-notice.md")
     }
@@ -151,6 +148,15 @@
   }
 }
 
+// `testOptions { packagingOptions { ... } }` configures the library's own packaging options, so
+// pickFirst entries there override the excludes above and the AAR ships its own copies of
+// libc++_shared.so and libfbjni.so. Only the instrumentation test APK needs one copy of each.
+androidComponents {
+  onVariants(selector().all()) { variant ->
+    variant.androidTest?.packaging?.jniLibs?.pickFirsts?.addAll(["**/libfbjni.so", "**/libc++_shared.so"])
+  }
+}
+
 ksp {
   arg("room.generateKotlin", "true")
```

I checked the instrumentation test APK (`./gradlew :expo-updates:assembleDebugAndroidTest`, built but not run): with the two lines simply removed it builds without `libc++_shared.so`/`libfbjni.so`, and with the change above it contains both again. The AAR contains only `libexpo-updates.so` in both cases, and the brownfield host builds.

I'm happy to open a PR with this if it looks right.

### Environment

- expo 58.0.25, expo-updates 58.0.22, expo-brownfield 58.0.20
- react-native 0.88.1 (New Architecture), Hermes
- AGP 9.2.1, Gradle 9.4.1, NDK 27.1.12297006 (default) / 27.0.12077973 (to trigger the failure)
- macOS 27.2, JDK 21
