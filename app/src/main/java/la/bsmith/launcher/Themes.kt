package la.bsmith.launcher

import androidx.compose.runtime.Composable

/**
 * The registry of looks. Each theme is one file holding a composable that
 * draws the whole home screen from [LauncherShared]; the pickers in every
 * theme list this enum, so a new look is one file and one line here.
 */
enum class Theme(val label: String, val content: @Composable (LauncherShared) -> Unit) {
    DAPPLE_TV("Dapple TV", { DappleTvTheme(it) }),
    TERMINAL("Terminal", { TerminalTheme(it) }),
    SNITCH("Mintendo Snitch", { SnitchTheme(it) }),
    GAME_QUEEN("Game Queen", { GameQueenTheme(it) }),
    AMBIENT("Ambient clock", { AmbientTheme(it) }),
    EDITORIAL("Editorial", { EditorialTheme(it) });

    companion object {
        /** The theme saved or sent as NAME, a name from before the renames included; null when none is. */
        fun byName(name: String?): Theme? = entries.firstOrNull { it.name == name } ?: LegacyThemes[name]
    }
}

// Theme names used by builds before the themes were renamed, so a saved
// choice and its Light or Dark setting, or a theme sent by ThemeReceiver's
// caller, still resolve.
val LegacyThemes = mapOf("APPLE_TV" to Theme.DAPPLE_TV, "SWITCH" to Theme.SNITCH, "GAME_BOY" to Theme.GAME_QUEEN)
