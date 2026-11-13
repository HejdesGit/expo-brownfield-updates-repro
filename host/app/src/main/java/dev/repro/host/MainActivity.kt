package dev.repro.host

import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import dev.repro.brownfield.BrownfieldActivity
import dev.repro.brownfield.ReactNativeFragment
import dev.repro.brownfield.ReactNativeHostManager
import dev.repro.brownfield.setUpNativeBackHandling
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A native screen that embeds the React Native surface from the brownfield AAR below a
 * native header, using the AAR's ReactNativeFragment (the same fragment that
 * expo-brownfield's `showReactNativeFragment()` adds). The fragment creates its view with
 * ReactNativeViewFactory.createSurface -> ReactDelegate.loadApp().
 */
class MainActivity : BrownfieldActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ReactNativeHostManager.shared.initialize(application)

    val header =
        TextView(this).apply {
          val started = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
          text = "Native host Activity\npid ${Process.myPid()} · activity created $started"
          setTextColor(Color.WHITE)
          setBackgroundColor(Color.parseColor("#263238"))
          textSize = 16f
          setPadding(32, 32, 32, 32)
        }

    val root =
        LinearLayout(this).apply {
          orientation = LinearLayout.VERTICAL
          setBackgroundColor(Color.parseColor("#ECEFF1"))
          addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
          addView(
              FrameLayout(this@MainActivity).apply { id = R.id.rn_container },
              LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
          )
        }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
    setContentView(root)
    // On a configuration change or Activity.recreate() the FragmentManager restores the
    // fragment into the same container, so only add it the first time.
    if (savedInstanceState == null) {
      supportFragmentManager.commit { add(R.id.rn_container, ReactNativeFragment()) }
    }
    setUpNativeBackHandling()
  }
}
