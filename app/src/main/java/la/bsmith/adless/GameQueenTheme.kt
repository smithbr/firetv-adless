package la.bsmith.adless

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

// The four DMG greens, darkest to lightest; a variant only re-assigns them.
private val Darkest = Color(0xFF0F380F)
private val Dark = Color(0xFF306230)
private val Light = Color(0xFF8BAC0F)
private val Lightest = Color(0xFF9BBC0F)
// The Dark look's screen: a true black behind the greens.
private val Black = Color(0xFF000000)

private const val LIGHT = "Light"
private const val DARK = "Dark"

/** Which green plays which role. */
private class GbPalette(val screen: Color, val tile: Color, val ink: Color, val dim: Color, val focus: Color)

private val LightPalette = GbPalette(screen = Lightest, tile = Light, ink = Darkest, dim = Dark, focus = Darkest)
// "Pocket black": black screen, darkest-green tiles and panels, one mid green for everything drawn.
private val DarkPalette = GbPalette(screen = Black, tile = Darkest, ink = Light, dim = Light, focus = Light)

private val Pixel = FontFamily(Font(R.font.press_start_2p))
private val TileGap = 12.dp
// Six square tiles across the safe area: 134 dp each on a 960 dp screen,
// so 9 sp pixel capitals fit 13 to a line.
private const val COLUMNS = 6
private val SafeX = 48.dp
private val SafeY = 27.dp

private sealed interface Tile {
    val label: String
    val key: String

    /** A full-width section label, not focusable. */
    data class Label(override val label: String) : Tile {
        override val key get() = "label:$label"
    }

    data class App(val entry: AppEntry, val inFavorites: Boolean) : Tile {
        override val label get() = entry.label
        override val key get() = (if (inFavorites) "fav:" else "app:") + entry.id
    }

    data class Setting(val entry: SystemEntry) : Tile {
        override val label get() = entry.label
        override val key get() = "setting:${entry.label}"
    }

    data object Hidden : Tile {
        override val label get() = "Hidden apps"
        override val key get() = "hidden"
    }

    data object ThemePick : Tile {
        override val label get() = "Theme"
        override val key get() = "theme"
    }

    data class Look(val current: String) : Tile {
        override val label get() = "Look: $current"
        override val key get() = "look"
    }
}

/**
 * The Game Queen look: four greens, a pixel font, a 6-wide grid of square
 * tiles that carry the app's name in up to two lines, a 3 dp border on the
 * focused tile and no animation anywhere, the focused name under the grid,
 * A/B hints below; no title line. The grid keeps its own bounds above the name and the
 * hints, so the focused tile is always scrolled fully into view.
 */
@Composable
fun GameQueenTheme(shared: LauncherShared) {
    // Dark unless Light was chosen: the light screen is bright on a TV.
    val variant = if (shared.variant == LIGHT) LIGHT else DARK
    val palette = if (variant == LIGHT) LightPalette else DarkPalette

    // FAVORITES, then APPS, then SETTINGS, each under a full-width label, so
    // an app added to favorites visibly moves up into its own section.
    val tiles = remember(shared.favorites, shared.apps, shared.settings, variant) {
        buildList<Tile> {
            if (shared.favorites.isNotEmpty()) {
                add(Tile.Label("FAVORITES"))
                shared.favorites.forEach { add(Tile.App(it, inFavorites = true)) }
            }
            add(Tile.Label("APPS"))
            shared.apps.forEach { add(Tile.App(it, inFavorites = false)) }
            add(Tile.Label("SETTINGS"))
            shared.settings.forEach { add(Tile.Setting(it)) }
            add(Tile.Hidden)
            add(Tile.ThemePick)
            add(Tile.Look(variant))
        }
    }
    val firstEntry = tiles.indexOfFirst { it !is Tile.Label }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showLook by remember { mutableStateOf(false) }
    val focusedLabel = remember { mutableStateOf("") }

    val first = remember { FocusRequester() }
    LaunchedEffect(firstEntry >= 0) {
        repeat(10) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    BackHandler { runCatching { first.requestFocus() } }

    val menuStyle = remember(variant) {
        if (variant == LIGHT) {
            MenuStyle(
                panel = palette.screen, text = palette.ink, dim = palette.dim,
                focusBackground = palette.ink, focusText = palette.screen,
                border = palette.ink, font = Pixel, fontSize = 11.sp, uppercase = true, rowHeight = 40.dp,
            )
        } else {
            // Panels in the tile green; the focused line keeps that fill under
            // the mid-green text and shows by its border.
            MenuStyle(
                panel = palette.tile, text = palette.ink, dim = palette.dim,
                focusBackground = palette.tile, focusText = palette.ink,
                border = palette.ink, font = Pixel, fontSize = 11.sp, uppercase = true, rowHeight = 40.dp,
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(palette.screen).padding(horizontal = SafeX, vertical = SafeY)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(Tags.APPS),
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            verticalArrangement = Arrangement.spacedBy(TileGap),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(
                tiles,
                key = { _, tile -> tile.key },
                span = { _, tile -> GridItemSpan(if (tile is Tile.Label) maxLineSpan else 1) },
            ) { index, tile ->
                if (tile is Tile.Label) {
                    PixelText(tile.label, size = 11.sp, color = palette.dim, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    return@itemsIndexed
                }
                // Column position within the section, for the row-end cancels.
                val sectionStart = (index downTo 0).first { tiles[it] is Tile.Label } + 1
                val column = (index - sectionStart) % COLUMNS
                val sectionEnd = tiles.drop(index + 1).indexOfFirst { it is Tile.Label }.let { if (it < 0) tiles.lastIndex else index + it }
                PixelTile(
                    tile = tile,
                    palette = palette,
                    onFocused = { focusedLabel.value = tile.label },
                    onClick = {
                        when (tile) {
                            is Tile.App -> shared.open(tile.entry)
                            is Tile.Setting -> shared.openSetting(tile.entry)
                            Tile.Hidden -> showHidden = true
                            Tile.ThemePick -> showThemes = true
                            is Tile.Look -> showLook = true
                            is Tile.Label -> Unit
                        }
                    },
                    onLongClick = (tile as? Tile.App)?.let { app -> { menuFor = app.entry } },
                    modifier = Modifier
                        .then(if (tile is Tile.App) Modifier.testTag(Tags.TILE) else Modifier)
                        .then(if (index == firstEntry) Modifier.focusRequester(first) else Modifier)
                        .focusProperties {
                            if (column == 0) left = FocusRequester.Cancel
                            if (column == COLUMNS - 1 || index == sectionEnd) right = FocusRequester.Cancel
                        },
                )
            }
        }
        NameSlot(focusedLabel, palette.ink)
        Spacer(modifier = Modifier.height(10.dp))
        PixelText("A:OPEN  B:BACK  HOLD A:EDIT", size = 10.sp, color = palette.dim)
    }

    menuFor?.let { app ->
        StyledAppMenu(
            style = menuStyle,
            app = app,
            isFavorite = app.id in shared.favoriteIds,
            onToggleFavorite = { shared.toggleFavorite(app) },
            onHide = { shared.hide(app) },
            onUninstall = { shared.uninstall(app) },
            onDismiss = { menuFor = null },
        )
    }
    if (showHidden) StyledHiddenList(menuStyle, shared.hiddenApps, shared.unhide) { showHidden = false }
    if (showThemes) StyledThemePicker(menuStyle, shared.theme, shared.setTheme) { showThemes = false }
    if (showLook) StyledChoice(menuStyle, "Look", listOf(DARK, LIGHT), variant, shared.setVariant) { showLook = false }
}

@Composable
private fun NameSlot(label: State<String>, color: Color) {
    PixelText(label.value.uppercase(), size = 14.sp, color = color, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun PixelText(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color = Darkest,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        color = color,
        fontFamily = Pixel,
        fontSize = size,
        lineHeight = size * 1.5f,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier,
    )
}

/**
 * A square tile with the name in capitals, centred, wrapped to two lines at
 * most and cut there with no ellipsis.
 */
@Composable
private fun PixelTile(
    tile: Tile,
    palette: GbPalette,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(1f).onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.tile,
            focusedContainerColor = palette.tile,
            pressedContainerColor = palette.tile,
            contentColor = palette.ink,
            focusedContentColor = palette.ink,
            pressedContentColor = palette.ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, palette.focus), shape = RectangleShape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            PixelText(tile.label.uppercase(), size = 9.sp, color = palette.ink, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}
