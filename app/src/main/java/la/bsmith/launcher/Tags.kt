package la.bsmith.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.Date

/**
 * Test tags that tests/device finds in `uiautomator dump` as resource ids.
 * The same names in every theme, so one scenario covers all six.
 */
object Tags {
    /** The favorites row. */
    const val FAVORITES = "favorites"
    /** The list or grid of every app. */
    const val APPS = "apps"
    /** One app's tile or line. */
    const val TILE = "tile"
    /** The long-press menu. */
    const val APP_MENU = "app_menu"
    /** The hidden apps list. */
    const val HIDDEN = "hidden"
}

/** On a compose root (the screen, each dialog): reports test tags as resource ids. */
fun Modifier.exposeTestTags(): Modifier = semantics { testTagsAsResourceId = true }

/** What every clock shows: now, or the devicetest build's frozen time. */
fun clockNow(): Date = DeviceTest.clockMillis?.let(::Date) ?: Date()
