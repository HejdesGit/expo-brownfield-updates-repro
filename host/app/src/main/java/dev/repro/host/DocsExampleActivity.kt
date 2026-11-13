package dev.repro.host

import android.os.Bundle
import dev.repro.brownfield.BrownfieldActivity
import dev.repro.brownfield.showReactNativeFragment

/**
 * The Activity from expo-brownfield's "isolated approach" guide, verbatim apart from the name.
 * Opened with the deep link repro://docs. Used to check how expo/expo#50943's
 * Activity.recreate() behaves with the documented setup.
 */
class DocsExampleActivity : BrownfieldActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    showReactNativeFragment()
  }
}
