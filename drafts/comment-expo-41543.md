<!--
DRAFT, not posted. Target: comment on https://github.com/expo/expo/issues/41543
(cc the author of #50943 only if the maintainers want that; no @-mentions in this draft)
-->

I can reproduce the blank-surface part of this on SDK 58 in a brownfield setup (`expo-brownfield` release AAR embedded in a native Android app), with stock `react-native` 0.88.1 and unpatched packages. I also tested #50943 against it.

Repro: https://github.com/HejdesGit/expo-brownfield-updates-repro (local Expo Updates server, minimal Kotlin host, scripts for each step; the README has recordings, screenshots and logcat).

**Versions:** expo 58.0.25, expo-updates 58.0.22, expo-brownfield 58.0.20, react-native 0.88.1 (New Architecture, bridgeless, Hermes), release builds, Android 17 emulator.

**Scenario:** update A is running. Update B throws at module scope, before `registerRootComponent`, is downloaded and then launched on the next cold start.

**Without changes:** error recovery falls back to A and reloads the host, but the embedded view stays blank in that process. The logs show `handleHostException` → destroy → `falling back to older update` → `getOrCreateReloadTask()` → `Restarting previously running React Native Surfaces`, with no `Running "main"` afterwards, because the destroy has already cleared `attachedSurfaces`. The next cold start shows A. Video: [`media/bug1-unpatched.mp4`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/media/bug1-unpatched.mp4)

**With #50943 applied to expo-updates 58.0.22:** in this brownfield setup the view came back in 1 of 14 runs. A build with log lines added to `restart()` shows the same values in every run:

```
restart(): hostWasDestroyed=false activity=null lifecycleState=BEFORE_CREATE
```

- `hostWasDestroyed` is `false` because error recovery calls `restart()` while the destroy task from `handleHostException()` is still running. `Resetting ReactContext ref` is logged about 1 ms after `restart()`:
  ```
  17:40:04.969 ReactHost{0}.getOrCreateDestroyTask(): Starting React Native destruction
  17:40:04.973 Pr50943Diag: restart(): hostWasDestroyed=false activity=null lifecycleState=BEFORE_CREATE
  17:40:04.974 ReactHost{0}.reload(): Waiting for destroy to finish, before reloading React Native.
  17:40:04.974 ReactHost{0}.getOrCreateDestroyTask(): Resetting ReactContext ref
  ```
- `activity` is `null`: the reference that `RelaunchProcedure` passes comes from `reactContext.currentActivity` in `onDidCreateReactInstance`, and it was null in these runs.

In the one run where it did recover, the host Activity was recreated and a restored fragment started a new surface. With the Activity from the expo-brownfield guide (`showReactNativeFragment()` in `onCreate`) it never recovered. Per-run results: [`logs/pr50943-repeated-runs.txt`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/logs/pr50943-repeated-runs.txt)

So for brownfield, #50943 as it stands is not enough, and the check on `currentReactContext == null` looks timing-dependent in general, not only in brownfield.

**What did work in brownfield:** restarting the surface from the view side instead of recreating the Activity. In expo-brownfield's `ReactNativeViewFactory.createSurface()` the factory creates the surface itself (`reactHost.createSurface()` + `start()`, as `ReactDelegate.loadApp()` does on bridgeless) and registers `addBeforeDestroyListener` and a `ReactInstanceEventListener`. When a new instance comes up after a teardown and the surface is not running, it calls `stop()`, waits for it, and then calls `start()` on the main thread if `currentReactContext` is still the new context. That recovered 6 of 6 runs in the same process, about 0.5 s after the crash, without recreating the Activity: [`fixes/viewfactory/ReactNativeViewFactory.kt`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/fixes/viewfactory/ReactNativeViewFactory.kt), video [`media/viewfactory-fix.mp4`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/media/viewfactory-fix.mp4).

It only covers surfaces created by the brownfield factory. Keeping surfaces attached across a destroy that is followed by a reload, in React Native or in expo-updates, would cover regular apps as well. I'm happy to turn either piece into a PR if that's useful.
