package la.bsmith.adless

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
    EDITORIAL("Editorial", { EditorialTheme(it) }),
}
