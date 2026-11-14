<!--
DRAFT, not posted. Target: comment on https://github.com/expo/expo/pull/50943
No @-mentions in this draft.
-->

I tested this PR in a brownfield setup (an `expo-brownfield` release AAR embedded in a native Android app) on SDK 58, with stock `react-native` 0.88.1 and unpatched packages otherwise. Short version: there the view came back in 1 of 14 runs, and the reasons match what the [comment from 4 October](https://github.com/expo/expo/pull/50943#issuecomment-5980397178) describes. Brownfield adds one more problem: there is no `ReactActivity`/`ReactDelegate` to restart the surface from.

Repro: https://github.com/HejdesGit/expo-brownfield-updates-repro (local Expo Updates server, minimal Kotlin host, scripts, recordings and logcat).

**Versions:** expo 58.0.25, expo-updates 58.0.22, expo-brownfield 58.0.20, react-native 0.88.1 (New Architecture, bridgeless, Hermes), release builds, Android 17 emulator.

**Scenario:** update A is running. Update B throws at module scope, before `registerRootComponent`, is downloaded and then launched on the next cold start.

**Without the PR:** error recovery falls back to A and reloads the host, but the embedded view stays blank in that process: `handleHostException` → destroy → `falling back to older update` → `getOrCreateReloadTask()` → `Restarting previously running React Native Surfaces`, and no `Running "main"` afterwards. The next cold start shows A. Video: [`media/bug1-unpatched.mp4`](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/media/bug1-unpatched.mp4)

**With the PR applied to expo-updates 58.0.22:** recovered in 1 of 14 runs ([per-run results](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/logs/pr50943-repeated-runs.txt)). A build with log lines added to `restart()` logged the same values in all six of its runs:

```
restart(): hostWasDestroyed=false activity=null lifecycleState=BEFORE_CREATE
```

- `hostWasDestroyed` is `false` because error recovery calls `restart()` while the destroy task from `handleHostException()` is still running; `Resetting ReactContext ref` comes about 1 ms later:
  ```
  17:40:04.969 ReactHost{0}.getOrCreateDestroyTask(): Starting React Native destruction
  17:40:04.973 Pr50943Diag: restart(): hostWasDestroyed=false activity=null lifecycleState=BEFORE_CREATE
  17:40:04.974 ReactHost{0}.reload(): Waiting for destroy to finish, before reloading React Native.
  17:40:04.974 ReactHost{0}.getOrCreateDestroyTask(): Resetting ReactContext ref
  ```
  `lifecycleState` is already `BEFORE_CREATE` at that point, so checking it as well, as suggested in the comment above, would catch this case.
- `activity` is `null`: `RelaunchProcedure` passes what `onDidCreateReactInstance` read from `reactContext.currentActivity`, and it was null in these runs.

In the one run that recovered, the host Activity was recreated and the restored fragment started a new surface. With the Activity from the expo-brownfield guide (`showReactNativeFragment()` in `onCreate`) it never recovered. Even when it works, recreating the host's Activity is a large side effect in brownfield, where that Activity belongs to the native app.

**What worked in brownfield:** restarting the surface from expo-brownfield's `ReactNativeViewFactory` instead, without touching expo-updates or the Activity ([code](https://github.com/HejdesGit/expo-brownfield-updates-repro/blob/main/fixes/viewfactory/ReactNativeViewFactory.kt)). The factory creates the surface itself (`reactHost.createSurface()` + `start()`, as `ReactDelegate.loadApp()` does on bridgeless) and:

- treats a teardown as fatal only if the context being torn down got `onHostDestroy()` first (`destroy()` does that, `reload()` only pauses), so ordinary reloads are left to React Native;
- when the next instance comes up, stops the surface, waits for that off the main thread, hands the Activity back to the host and to the new context (destroy resets it, and since the host is already `RESUMED`, `ReactHost.onHostResume()` alone doesn't reach the context), and starts the surface on the main thread, if the surface was not disposed meanwhile and `currentReactContext` is still the new context.

Results with the same script: unpatched 4 of 4 runs blank; with this change 10 of 10 recovered in the same process (5 with each Activity), 0.25–0.51 s after the crash, and `Alert.alert` worked afterwards in every run. Three `Updates.reloadAsync()` in a row were left to React Native's own surface restart. [Per-run results and logcat (filtered to the app's processes)](https://github.com/HejdesGit/expo-brownfield-updates-repro/tree/main/logs/viewfactory-runs). Not tested yet: disposing the surface during a recovery, several surfaces, and a fatal error while the Activity is in the background.

This only covers surfaces created by the brownfield factory, so it complements this PR rather than replacing it. I'm happy to open a PR against expo-brownfield's template if that direction looks right.
