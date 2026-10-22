package la.bsmith.launcher

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Catppuccin Mocha (catppuccin.com/palette): text on base, overlay 1 for
// hints, a mauve cursor in menus. No scanlines. Sections in pink,
// blue and green; names colored like ls: built-in apps blue, sideloaded
// green, settings teal; menu actions yellow, peach and red by risk. A peach
// clock, footer keys in yellow, and rules in surface 1 so they recede. Set
// in JetBrains Mono (licenses/JetBrainsMono-OFL.txt).
private val Mocha = TerminalPalette(
    screen = Color(0xFF1E1E2E),
    ink = Color(0xFFCDD6F4),
    dim = Color(0xFF7F849C),
    label = Color(0xFF89B4FA),
    cursor = Color(0xFFCBA6F7),
    scanlines = false,
    favoritesAccent = Color(0xFFF5C2E7),
    appsAccent = Color(0xFF89B4FA),
    settingsAccent = Color(0xFFA6E3A1),
    systemApp = Color(0xFF89B4FA),
    sideloadedApp = Color(0xFFA6E3A1),
    setting = Color(0xFF94E2D5),
    favoriteAction = Color(0xFFF9E2AF),
    hideAction = Color(0xFFFAB387),
    uninstallAction = Color(0xFFF38BA8),
    clock = Color(0xFFFAB387),
    hintKey = Color(0xFFF9E2AF),
    rule = Color(0xFF45475A),
    font = FontFamily(
        Font(R.font.jetbrains_mono_regular),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    ),
)

/** The Terminal look in Catppuccin Mocha's colors; the layout and keys are the same. */
@Composable
fun CatppuccinTheme(shared: LauncherShared) = TerminalTheme(shared, Mocha)
