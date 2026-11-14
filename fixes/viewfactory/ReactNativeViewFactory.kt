package dev.repro.brownfield

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import android.util.Log
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceEventListener
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.LifecycleState
import com.facebook.react.interfaces.fabric.ReactSurface
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import java.util.Collections
import java.lang.ref.WeakReference
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
    val recovery = SurfaceRecovery(reactHost, surface, activity, backBtnHandlerFor(activity))

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

  private fun backBtnHandlerFor(activity: FragmentActivity): DefaultHardwareBackBtnHandler =
      activity as? DefaultHardwareBackBtnHandler
          ?: DefaultHardwareBackBtnHandler { activity.onBackPressedDispatcher.onBackPressed() }

  private fun bindHostLifecycle(activity: FragmentActivity, reactHost: ReactHost) {
    if (!hostBoundActivities.add(activity)) {
      return
    }

    // `ReactDelegate.onHostResume()` throws unless the host Activity implements
    // `DefaultHardwareBackBtnHandler`. In a brownfield app the surface is usually
    // embedded in an existing Activity that does not implement it, so resume the
    // host directly with a handler backed by the Activity's OnBackPressedDispatcher
    // (used only if the Activity itself isn't one).
    val backBtnHandler = backBtnHandlerFor(activity)

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
 * Restarts [surface] when [reactHost] comes back with a new React instance after a fatal teardown.
 *
 * A fatal JS error makes ReactHostImpl.handleHostException() call destroy(). destroy() stops every
 * attached surface, clears ReactHostImpl.attachedSurfaces and resets the host's current Activity.
 * When something then reloads the host (expo-updates error recovery calls ReactHost.restart()),
 * the reload only restarts surfaces in attachedSurfaces, so the embedded view stays empty, and the
 * new instance has no Activity. This class notices the fatal teardown, hands the Activity back to
 * the host and starts the surface again on the new instance.
 *
 * An ordinary reload (Updates.reloadAsync(), dev reload) also runs the before-destroy listeners,
 * but it keeps attached surfaces and restarts them itself. To leave those to React Native, this
 * class only acts when the context being torn down got onHostDestroy() first: destroy() moves the
 * host to onHostDestroy() before it calls the before-destroy listeners, reload() only pauses it.
 * The lifecycle state alone is not enough, because the Activity can resume the host in between.
 */
private class SurfaceRecovery(
    private val reactHost: ReactHost,
    private val surface: ReactSurface,
    private val activity: FragmentActivity,
    private val backBtnHandler: DefaultHardwareBackBtnHandler,
) {
  @Volatile private var lostToFatalTeardown = false
  @Volatile private var disposed = false

  // The context that most recently got onHostDestroy(), and the listener on the current context.
  private var hostDestroyedContext: WeakReference<ReactContext>? = null
  private var watched: Pair<ReactContext, LifecycleEventListener>? = null

  // Called on the UI thread.
  private fun watch(context: ReactContext) {
    watched?.let { (old, listener) -> old.removeLifecycleEventListener(listener) }
    val listener =
        object : LifecycleEventListener {
          override fun onHostResume() = Unit

          override fun onHostPause() = Unit

          override fun onHostDestroy() {
            hostDestroyedContext = WeakReference(context)
          }
        }
    context.addLifecycleEventListener(listener)
    watched = context to listener
  }

  // Called on the UI thread, from the destroy/reload task, after surfaces were stopped and while
  // currentReactContext is still the context being torn down.
  private val beforeDestroyListener: () -> Unit = {
    val tearingDown = reactHost.currentReactContext
    val gotHostDestroy = tearingDown != null && hostDestroyedContext?.get() === tearingDown
    if (gotHostDestroy || reactHost.lifecycleState == LifecycleState.BEFORE_CREATE) {
      lostToFatalTeardown = true
    }
    hostDestroyedContext = null
  }

  private val instanceListener =
      object : ReactInstanceEventListener {
        // Called on the UI thread.
        override fun onReactContextInitialized(context: ReactContext) {
          if (disposed) {
            return
          }
          watch(context)
          if (!lostToFatalTeardown) {
            return
          }
          lostToFatalTeardown = false
          if (surface.isRunning) {
            return
          }
          Log.w(TAG, "React instance recreated after teardown; restarting surface ${surface.moduleName}")
          thread(name = "brownfield-surface-recovery") { recover(context) }
        }
      }

  private fun recover(context: ReactContext) {
    if (disposed) {
      return
    }
    // destroy() already stopped the surface; stop() makes sure it is detached from the old
    // instance before it is started on the new one.
    val stopTask = surface.stop()
    val stopped =
        try {
          stopTask.waitForCompletion(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          false
        }
    if (!stopped) {
      // The stop may still run later and would tear down a restarted surface. Leave it blank.
      Log.e(TAG, "Timed out stopping surface ${surface.moduleName}; not restarting it")
      return
    }
    if (stopTask.isFaulted()) {
      // Nothing was running on the new instance; the surface is detached, so starting is safe.
      Log.w(TAG, "stop() failed before restart: ${stopTask.getError()}")
    }
    UiThreadUtil.runOnUiThread {
      // dispose() also runs on the UI thread, so this check cannot race with it.
      if (disposed || reactHost.currentReactContext !== context || surface.isRunning) {
        return@runOnUiThread
      }
      restoreActivity(context)
      surface.start()
      Log.w(
          TAG,
          "Surface ${surface.moduleName} restarted; hasCurrentActivity=${context.hasCurrentActivity()}",
      )
    }
  }

  // destroy() resets the host's Activity, and the reload resumes the new context without one
  // (moveToOnHostResume(context, null)). Once the host is RESUMED, ReactHost.onHostResume() returns
  // early and never reaches the context, so the Activity is handed to both. If the Activity is
  // not in the foreground right now, this happens on its next onResume. Called on the UI thread.
  private fun restoreActivity(context: ReactContext) {
    if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
      reactHost.onHostResume(activity, backBtnHandler)
      if (!context.hasCurrentActivity()) {
        context.onHostResume(activity)
      }
      return
    }
    activity.lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onResume(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
            if (!disposed && reactHost.currentReactContext === context && !context.hasCurrentActivity()) {
              context.onHostResume(activity)
            }
          }

          override fun onDestroy(owner: LifecycleOwner) {
            owner.lifecycle.removeObserver(this)
          }
        }
    )
  }

  // Constructed on the UI thread.
  init {
    reactHost.currentReactContext?.let(::watch)
    reactHost.addBeforeDestroyListener(beforeDestroyListener)
    reactHost.addReactInstanceEventListener(instanceListener)
  }

  /** Must be called on the UI thread. */
  fun dispose() {
    disposed = true
    watched?.let { (context, listener) -> context.removeLifecycleEventListener(listener) }
    watched = null
    reactHost.removeBeforeDestroyListener(beforeDestroyListener)
    reactHost.removeReactInstanceEventListener(instanceListener)
  }

  companion object {
    private const val TAG = "BrownfieldSurface"
  }
}
