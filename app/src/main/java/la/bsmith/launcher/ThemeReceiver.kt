package la.bsmith.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// am broadcast starts the result code at 0 and prints it, so tools/adb.sh
// reads anything else as a failure.
private const val FAILED = 1

/**
 * Reads and sets the theme over adb, for firetv theme and so install_launcher
 * (tools/adb.sh) keeps the person's theme when it has to replace the launcher
 * (which clears its data):
 *
 *     am broadcast -n la.bsmith.launcher/.ThemeReceiver \
 *         [--es theme TERMINAL] [--es variant.SNITCH Dark]
 *
 * theme sets the theme; variant.NAME sets that theme's Light or Dark. Names
 * from before the themes were renamed work too. The answer, after any change,
 * is the current theme's name on the first line, then
 * "NAME<tab>label<tab>variant" per theme in picker order. An unknown theme
 * changes nothing and fails, with the reason as the answer; an unknown
 * variant.NAME is skipped, since a removed theme has nothing to set. Like
 * [AppLabelsReceiver], only holders of DUMP (the adb shell) may call it.
 */
class ThemeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val store = LauncherStore(context.applicationContext)
        val name = intent.getStringExtra("theme")
        val variants = intent.extras?.keySet().orEmpty().filter { it.startsWith("variant.") }.mapNotNull { key ->
            Theme.byName(key.removePrefix("variant."))?.let { theme -> intent.getStringExtra(key)?.let { theme to it } }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val theme = Theme.byName(name)
                if (name != null && theme == null) {
                    pending.resultCode = FAILED
                    pending.resultData = "No theme named $name"
                    return@launch
                }
                theme?.let { store.setTheme(it) }
                for ((variantTheme, value) in variants) store.setVariant(variantTheme, value)
                val current = store.variants.first()
                pending.resultData = buildString {
                    append(store.theme.first().name)
                    for (t in Theme.entries) append("\n${t.name}\t${t.label}\t${current[t] ?: ""}")
                }
            } catch (e: Exception) {
                // An exception here would reach the default handler and kill
                // the launcher, which may be Home; fail the broadcast instead.
                pending.resultCode = FAILED
                pending.resultData = e.toString()
            } finally {
                pending.finish()
            }
        }
    }
}
