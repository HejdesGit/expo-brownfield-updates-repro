package dev.repro.brownfield

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.util.Log
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.interfaces.fabric.ReactSurface
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import java.util.Collections
import java.util.WeakHashMap

/**
 * A mounted React Native surface, owned by whoever created it.
 *
 * [dispose] stops the surface, which unmounts its JS component tree so anything
 * that tree holds — message listeners, timers, media players — is released. A
 * host that embeds a surface somewhere shorter-lived than the Activity (a Compose
 * node, a fragment, a tab) must dispose it, or the surface keeps running off
 * screen and a second one is created the next time the container appears.
 */
class ReactNativeSurfaceHandle
internal constructor(
    val view: FrameLayout,
    private val onDispose: () -> Unit,
) {
  private var isDisposed = false

  /** Stops the surface. Idempotent. */
  fun dispose() {
    if (isDisposed) {
      return
    }
    isDisposed = true
    onDispose()
  }
}

object ReactNativeViewFactory {
  // The ReactHost belongs to the Activity and outlives any single surface, so its
  // lifecycle forwarding is registered once per Activity, not once per surface.
  private val hostBoundActivities: MutableSet<FragmentActivity> =
      Collections.newSetFromMap(WeakHashMap<FragmentActivity, Boolean>())

  /**
   * Mounts [rootComponent] and returns a surface whose lifetime the caller owns.
   *
   * Use this when the surface can go away while the Activity stays up, and call
   * [ReactNativeSurfaceHandle.dispose] when it does.
   */
  fun createSurface(
      context: Context,
      activity: FragmentActivity,
      rootComponent: String,
      launchOptions: Bundle? = null,
  ): ReactNativeSurfaceHandle {
    val reactHost = ReactNativeHostManager.shared.getReactHost()!!
    bindHostLifecycle(activity, reactHost)

    // Same as ReactDelegate.loadApp() on bridgeless (createSurface + start), but we keep the
    // surface so that it can be restarted after the host tears down its React instance.
    val surface = reactHost.createSurface(activity, rootComponent, launchOptions)
    surface.start()
    val recovery = SurfaceRecovery(reactHost, surface)

    return ReactNativeSurfaceHandle(surface.view as FrameLayout) {
      recovery.dispose()
      surface.stop()
    }
  }

  /**
   * Mounts [rootComponent] for the whole life of [activity].
   *
   * Prefer [createSurface] unless the Activity exists solely to show this surface.
   */
  fun createFrameLayout(
      context: Context,
      activity: FragmentActivity,
      rootComponent: String,
      launchOptions: Bundle? = null,
  ): FrameLayout {
    val surface = createSurface(context, activity, rootComponent, launchOptions)

    activity.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onDestroy(owner: LifecycleOwner) {
            surface.dispose()
            owner.lifecycle.removeObserver(this) // Cleanup to avoid leaks
          }
        }
    )

    return surface.view
  }

  private fun bindHostLifecycle(activity: FragmentActivity, reactHost: ReactHost) {
    if (!hostBoundActivities.add(activity)) {
      return
    }

    // `ReactDelegate.onHostResume()` throws unless the host Activity implements
    // `DefaultHardwareBackBtnHandler`. In a brownfield app the surface is usually
    // embedded in an existing Activity that does not implement it, so resume the
    // host directly with a handler backed by the Activity's OnBackPressedDispatcher
    // (used only if the Activity itself isn't one).
    val backBtnHandler =
        activity as? DefaultHardwareBackBtnHandler
            ?: DefaultHardwareBackBtnHandler { activity.onBackPressedDispatcher.onBackPressed() }

    activity.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onResume(owner: LifecycleOwner) {
            reactHost.onHostResume(activity, backBtnHandler)
          }

          override fun onPause(owner: LifecycleOwner) {
            reactHost.onHostPause(activity)
          }

          override fun onDestroy(owner: LifecycleOwner) {
            reactHost.onHostDestroy(activity)
            hostBoundActivities.remove(activity)
            owner.lifecycle.removeObserver(this) // Cleanup to avoid leaks
          }
        }
    )
  }
}

/**
 * Restarts [surface] when [reactHost] comes back with a new React instance after a teardown.
 *
 * A fatal JS error makes ReactHostImpl.handleHostException() call destroy(), which stops every
 * attached surface and clears ReactHostImpl.attachedSurfaces. When something then reloads the
 * host (expo-updates error recovery calls ReactHost.restart()), the reload task only restarts
 * surfaces in attachedSurfaces, so the embedded view stays empty. This class notices the
 * teardown and starts the surface again on the new instance.
 */
private class SurfaceRecovery(
    private val reactHost: ReactHost,
    private val surface: ReactSurface,
) {
  @Volatile private var hostWasTornDown = false

  private val beforeDestroyListener: () -> Unit = { hostWasTornDown = true }

  private val instanceListener =
      object : ReactInstanceEventListener {
        // Called on the UI thread.
        override fun onReactContextInitialized(context: ReactContext) {
          if (!hostWasTornDown) {
            return
          }
          hostWasTornDown = false
          if (surface.isRunning) {
            return
          }
          Log.w(TAG, "React instance recreated after teardown; restarting surface ${surface.moduleName}")
          thread(name = "brownfield-surface-recovery") {
            // Make sure the surface is fully stopped and detached before starting it again.
            surface.stop().waitForCompletion(5, TimeUnit.SECONDS)
            UiThreadUtil.runOnUiThread {
              if (reactHost.currentReactContext === context && !surface.isRunning) {
                surface.start()
              }
            }
          }
        }
      }

  init {
    reactHost.addBeforeDestroyListener(beforeDestroyListener)
    reactHost.addReactInstanceEventListener(instanceListener)
  }

  fun dispose() {
    reactHost.removeBeforeDestroyListener(beforeDestroyListener)
    reactHost.removeReactInstanceEventListener(instanceListener)
  }

  companion object {
    private const val TAG = "BrownfieldSurface"
  }
}
