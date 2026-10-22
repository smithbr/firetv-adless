package la.bsmith.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reads and sets the theme over adb, so tools/install keeps the person's
 * theme when it has to replace the launcher (which clears its data):
 *
 *     am broadcast -n la.bsmith.launcher/.ThemeReceiver \
 *         [--es theme TERMINAL] [--es variant.SNITCH Dark]
 *
 * theme sets the theme; variant.NAME sets that theme's Light or Dark. The
 * answer, after any change, is the current theme's name on the first line,
 * then "NAME<tab>label<tab>variant" per theme in picker order. Like
 * [AppLabelsReceiver], only holders of DUMP (the adb shell) may call it.
 */
class ThemeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val store = LauncherStore(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                intent.getStringExtra("theme")?.let { name ->
                    Theme.entries.firstOrNull { it.name == name }?.let { store.setTheme(it) }
                }
                for (theme in Theme.entries) {
                    intent.getStringExtra("variant.${theme.name}")?.let { store.setVariant(theme, it) }
                }
                val variants = store.variants.first()
                pending.resultData = buildString {
                    append(store.theme.first().name)
                    for (theme in Theme.entries) append("\n${theme.name}\t${theme.label}\t${variants[theme] ?: ""}")
                }
            } finally {
                pending.finish()
            }
        }
    }
}
