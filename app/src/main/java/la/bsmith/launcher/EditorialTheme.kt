package la.bsmith.launcher

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val Serif = FontFamily(Font(R.font.playfair_display_italic, style = FontStyle.Italic))
private val TileShape = RoundedCornerShape(3.dp)
private val TileWidth = 104.dp
private val Margin = 72.dp
private const val LIGHT = "Light"
private const val DARK = "Dark"
private const val ROW_WIDTH = 0.62f

private class Paper(val background: Color, val ink: Color, val faint: Color, val panel: Color)

private val LightPaper = Paper(Color(0xFFF7F3EA), Color(0xFF1A1816), Color(0xFF8A8580), Color(0xFFFFFCF5))
private val DarkPaper = Paper(Color(0xFF1A1816), Color(0xFFF7F3EA), Color(0xFF8A8580), Color(0xFF26231F))

/** One word of the settings line. */
private sealed interface Word {
    val label: String

    data class Setting(val entry: SystemEntry) : Word {
        override val label get() = entry.label
    }

    data object Hidden : Word {
        override val label get() = "Hidden apps"
    }

    data object ThemePick : Word {
        override val label get() = "Theme"
    }

    data class Variant(val current: String) : Word {
        override val label get() = if (current == DARK) "Light" else "Dark"
    }
}

/**
 * The Editorial look: warm paper, the focused app's name set large in a
 * serif italic with its tagline, the clock small top right, one row of small
 * tiles bottom right with a thin underline for focus, and the settings as a
 * line of words bottom left. A title page.
 */
@Composable
fun EditorialTheme(shared: LauncherShared) {
    val variant = if (shared.variant == DARK) DARK else LIGHT
    val paper = if (variant == DARK) DarkPaper else LightPaper
    val tiles = remember(shared.favorites, shared.apps) { shared.favorites + shared.apps }
    // A short line: the first settings entry (Fire TV's menu, or its first
    // section when the menu is gone), Hidden apps, Theme, and the other look.
    val words = remember(shared.settings, variant) {
        listOfNotNull(shared.settings.firstOrNull()?.let<SystemEntry, Word> { Word.Setting(it) }) +
            Word.Hidden + Word.ThemePick + Word.Variant(variant)
    }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    val focusedApp = remember { mutableStateOf<AppEntry?>(null) }

    val first = remember { FocusRequester() }
    LaunchedEffect(tiles.isNotEmpty()) {
        repeat(10) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    BackHandler { runCatching { first.requestFocus() } }

    val menuStyle = remember(variant) {
        MenuStyle(
            panel = paper.panel, text = paper.ink, dim = paper.faint,
            focusBackground = paper.ink, focusText = paper.background,
            border = paper.ink, shape = RectangleShape, rowShape = RectangleShape,
            font = Serif, fontSize = 18.sp,
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(paper.background)) {
        Clock(color = paper.faint, modifier = Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = Margin))
        Title(focused = focusedApp, paper = paper, modifier = Modifier.align(Alignment.CenterStart).padding(start = Margin, end = Margin, bottom = 40.dp))
        // The row sits bottom right, in the space the title leaves.
        Column(modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(ROW_WIDTH).padding(bottom = 48.dp)) {
            LazyRow(
                state = rememberLazyListState(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 24.dp, end = Margin),
                modifier = Modifier.fillMaxWidth().testTag(Tags.FAVORITES).focusRestorer(),
            ) {
                itemsIndexed(tiles, key = { index, app -> "$index:${app.id}" }) { index, app ->
                    SmallTile(
                        app = app,
                        paper = paper,
                        onClick = { shared.open(app) },
                        onLongClick = { menuFor = app },
                        onFocused = { focusedApp.value = app },
                        modifier = Modifier
                            .testTag(Tags.TILE)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .focusProperties {
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == tiles.lastIndex) right = FocusRequester.Cancel
                            },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = Margin, bottom = 14.dp).focusRestorer(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            words.forEachIndexed { index, word ->
                if (index > 0) Text(text = " · ", color = paper.faint, fontSize = 14.sp)
                WordButton(
                    label = word.label,
                    paper = paper,
                    onClick = {
                        when (word) {
                            is Word.Setting -> shared.openSetting(word.entry)
                            Word.Hidden -> showHidden = true
                            Word.ThemePick -> showThemes = true
                            is Word.Variant -> shared.setVariant(if (variant == DARK) LIGHT else DARK)
                        }
                    },
                )
            }
        }
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
}

/** The focused app's name and tagline; the only reader of the focus. */
@Composable
private fun Title(focused: State<AppEntry?>, paper: Paper, modifier: Modifier) {
    val app = focused.value ?: return
    Column(modifier = modifier) {
        Text(
            text = app.label,
            color = paper.ink,
            fontFamily = Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 72.sp,
            lineHeight = 80.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Taglines.of(app.packageName)?.let { line ->
            Text(
                text = line,
                color = paper.faint,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** A small 16:9 tile with a thin underline, not a border, when focused. */
@Composable
private fun SmallTile(
    app: AppEntry,
    paper: Paper,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier = modifier.width(TileWidth)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(shape = TileShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = paper.ink.copy(alpha = 0.08f),
                focusedContainerColor = paper.ink.copy(alpha = 0.08f),
                pressedContainerColor = paper.ink.copy(alpha = 0.08f),
                contentColor = paper.ink,
                focusedContentColor = paper.ink,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
            border = ClickableSurfaceDefaults.border(),
        ) {
            val image = app.image
            if (image != null && app.isBanner) {
                Image(bitmap = image, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(TileShape))
            } else {
                Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                    Text(text = app.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (focused) paper.ink else Color.Transparent))
    }
}

/** A word of the settings line; underlined while focused. */
@Composable
private fun WordButton(label: String, paper: Paper, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
            contentColor = paper.faint,
            focusedContentColor = paper.ink,
            pressedContentColor = paper.ink,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(),
    ) {
        // The underline is drawn under the text itself: a box that fills the
        // width would take the whole line and leave no room for the next word.
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.drawBehind {
                if (focused) drawRect(paper.ink, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 1.5.dp.toPx()))
            },
        )
    }
}

@Composable
private fun Clock(color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockNow()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    Text(text = timeFormat.format(now), color = color, fontSize = 15.sp, modifier = modifier)
}
