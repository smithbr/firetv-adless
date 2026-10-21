package la.bsmith.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Answers `adb shell am broadcast -n la.bsmith.launcher/.AppLabelsReceiver`
 * with one "package<tab>name" line per app that has a TV or phone launcher
 * entry, for tools/menu, since Fire OS has no command that prints an app's
 * name. The manifest limits callers to holders of DUMP, which the adb shell
 * has and ordinary apps do not, so other apps cannot list what is installed.
 */
class AppLabelsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        resultData = AppRepository(context).labels().entries
            .joinToString("\n") { (pkg, label) -> "$pkg\t$label" }
    }
}
