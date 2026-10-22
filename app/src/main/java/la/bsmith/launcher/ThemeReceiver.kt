package la.bsmith.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Switches the theme for tools/firetv over adb:
 *
 *     am broadcast -n la.bsmith.launcher/.ThemeReceiver [--es theme TERMINAL]
 *
 * theme: a [Theme] name, saved as if picked in Settings; the home screen
 * redraws in it at once. Unknown names change nothing. Answers with one
 * "NAME<tab>label<tab>mark" line per theme, the mark "*" on the current one.
 * The manifest limits callers to holders of DUMP, as for [AppLabelsReceiver].
 */
class ThemeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = LauncherStore(context)
        val current = runBlocking {
            Theme.entries.firstOrNull { it.name == intent.getStringExtra("theme") }?.let { store.setTheme(it) }
            store.theme.first()
        }
        resultData = Theme.entries.joinToString("\n") { "${it.name}\t${it.label}\t${if (it == current) "*" else ""}" }
    }
}
