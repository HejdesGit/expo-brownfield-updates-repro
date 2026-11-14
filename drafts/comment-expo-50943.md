<!--
DRAFT, not posted. Target: comment on https://github.com/expo/expo/pull/50943
No @-mentions. The longer write-up with all details is in comment-expo-50943-long.md (not for posting).
-->

I tried this PR in a brownfield app, an `expo-brownfield` release AAR inside a plain Kotlin host, on SDK 58 with expo-updates 58.0.22 and react-native 0.88.1 (bridgeless). When a downloaded update throws at module scope, error recovery falls back to the previous update, but the embedded view stays blank until the next cold start. With the PR applied it came back in 1 of 14 runs.

I added a log line to `restart()`. It printed the same thing in all six runs I logged:

```
restart(): hostWasDestroyed=false activity=null lifecycleState=BEFORE_CREATE
```

Both of the PR's conditions are false here. `restart()` runs while the destroy from `handleHostException()` is still in progress, so `currentReactContext` isn't null yet. And `activity` is null, because the context had no current Activity when expo-updates read it. Also checking for `BEFORE_CREATE`, as the [comment from 4 October](https://github.com/expo/expo/pull/50943#issuecomment-5980397178) suggests, would catch the first part, but there would still be no Activity to recreate. In a brownfield app that Activity belongs to the native host, so recreating it is a bigger side effect than in a regular Expo app anyway.

I haven't tested a regular app, so I don't know why it works in the 6 November report.

Repro with scripts, logcat and recordings: https://github.com/HejdesGit/expo-brownfield-updates-repro

Restarting the surface from expo-brownfield's `ReactNativeViewFactory` instead brought the view back in 10 of 10 runs in the same setup. That code is in the repo too, but it depends on React Native internals, so I'm treating it as a local workaround for now.
