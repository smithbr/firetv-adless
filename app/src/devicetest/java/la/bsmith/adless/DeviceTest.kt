package la.bsmith.adless

import android.app.Activity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Intent extras for tests/device, compiled only into the devicetest build:
 *
 *     am start -n la.bsmith.adless.devicetest/la.bsmith.adless.MainActivity \
 *         --es theme TERMINAL --ei favorites 6 --ez freezeClock true --ez freezeBackdrop true
 *
 * theme: a [Theme] name, saved as if picked. favorites: fills favorites up
 * to N apps, in grid order, skipping the launchers. freezeClock: every clock reads 10:09 on 1 January 2026 and the
 * Terminal cursor stops blinking. freezeBackdrop: no backdrop photo. The
 * activity is singleTask, so the harness force-stops it before each start.
 */
object DeviceTest {
    var clockMillis: Long? = null
        private set
    var freezeBackdrop: Boolean = false
        private set

    fun apply(activity: Activity) {
        val intent = activity.intent ?: return
        if (intent.getBooleanExtra("freezeClock", false)) {
            clockMillis = Calendar.getInstance().apply { clear(); set(2026, Calendar.JANUARY, 1, 10, 9) }.timeInMillis
        }
        freezeBackdrop = intent.getBooleanExtra("freezeBackdrop", false)
        val store = LauncherStore(activity)
        runBlocking {
            intent.getStringExtra("theme")?.let { name ->
                store.setTheme(Theme.entries.firstOrNull { it.name == name } ?: error("No theme named $name"))
            }
            val count = intent.getIntExtra("favorites", 0)
            if (count > 0) {
                val current = store.favorites.first()
                AppRepository(activity).loadApps()
                    // Not the installed launchers: a misfired OK must not open the real one.
                    .filter { it.id !in current && !it.packageName.startsWith("la.bsmith.adless") }
                    .take((count - current.size).coerceAtLeast(0))
                    .forEach { store.toggleFavorite(it.id) }
            }
        }
    }
}
