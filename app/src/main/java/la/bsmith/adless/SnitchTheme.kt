package la.bsmith.adless

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private val Cyan = Color(0xFF00C3E3)
private val TileShape = RoundedCornerShape(8.dp)
private val TileSize = 118.dp
private val TileGap = 14.dp
private val SafeX = 48.dp
private val SafeY = 27.dp
private const val BOUNCE_MS = 150
private const val BOUNCE_SCALE = 1.08f

private const val LIGHT = "Light"
private const val DARK = "Dark"

/** The flat palette for one variant. */
private class SwitchPalette(val background: Color, val tile: Color, val text: Color, val dim: Color)

private val LightPalette = SwitchPalette(Color(0xFFEBEBEB), Color(0xFFD6D6D6), Color(0xFF2D2D2D), Color(0xFF7A7A7A))
private val DarkPalette = SwitchPalette(Color(0xFF2D2D2D), Color(0xFF444444), Color(0xFFEBEBEB), Color(0xFF9A9A9A))

/** What the small round buttons under the name stand for. */
private sealed interface Button {
    val label: String

    data class Setting(val entry: SystemEntry) : Button {
        override val label get() = entry.label
    }

    data object Hidden : Button {
        override val label get() = "Hidden apps"
    }

    data object ThemePick : Button {
        override val label get() = "Theme"
    }

    data class Variant(val current: String) : Button {
        override val label get() = "Look: $current"
    }
}

/**
 * The Mintendo Snitch look: a flat background, one row of square tiles with
 * a thick cyan focus border and a short scale bounce, the focused name
 * large under the row, and the settings as small round buttons under that.
 */
@Composable
fun SnitchTheme(shared: LauncherShared) {
    val variant = if (shared.variant == DARK) DARK else LIGHT
    val palette = if (variant == DARK) DarkPalette else LightPalette
    val tiles = remember(shared.favorites, shared.apps) { shared.favorites + shared.apps }
    val buttons = remember(shared.settings, variant) {
        shared.settings.map<SystemEntry, Button> { Button.Setting(it) } + Button.Hidden + Button.ThemePick + Button.Variant(variant)
    }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var showVariant by remember { mutableStateOf(false) }
    // The name under the row: whatever tile or button has focus. A state
    // object read only by the name slot, so a focus move recomposes that
    // one text and not the row or the buttons.
    val focusedLabel = remember { mutableStateOf("") }

    val first = remember { FocusRequester() }
    LaunchedEffect(tiles.isNotEmpty()) {
        repeat(10) {
            delay(50)
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    // Back returns to the first tile; the launcher never exits.
    BackHandler { runCatching { first.requestFocus() } }

    val menuStyle = remember(variant) {
        MenuStyle(
            panel = if (variant == DARK) Color(0xFF3A3A3A) else Color.White,
            text = palette.text,
            dim = palette.dim,
            focusBackground = Cyan,
            focusText = Color(0xFF2D2D2D),
            shape = RoundedCornerShape(12.dp),
            rowShape = RoundedCornerShape(8.dp),
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(palette.background)) {
        Clock(color = palette.text, modifier = Modifier.align(Alignment.End).padding(top = SafeY, end = SafeX))
        Spacer(modifier = Modifier.weight(1f))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TileGap),
            contentPadding = PaddingValues(horizontal = SafeX, vertical = 12.dp),
            modifier = Modifier.fillMaxWidth().testTag(Tags.FAVORITES).focusRestorer(),
        ) {
            itemsIndexed(tiles, key = { index, app -> "$index:${app.id}" }) { index, app ->
                SquareTile(
                    app = app,
                    palette = palette,
                    onClick = { shared.open(app) },
                    onLongClick = { menuFor = app },
                    onFocused = { focusedLabel.value = app.label },
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
        NameSlot(focusedLabel, palette.text)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = SafeX, top = 14.dp, bottom = SafeY).focusRestorer(),
        ) {
            for (button in buttons) {
                RoundButton(
                    button = button,
                    palette = palette,
                    onFocused = { focusedLabel.value = button.label },
                    onClick = {
                        when (button) {
                            is Button.Setting -> shared.openSetting(button.entry)
                            Button.Hidden -> showHidden = true
                            Button.ThemePick -> showThemes = true
                            is Button.Variant -> showVariant = true
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
    if (showVariant) StyledChoice(menuStyle, "Look", listOf(LIGHT, DARK), variant, shared.setVariant) { showVariant = false }
}

/** The large name under the row; the only reader of the focused label. */
@Composable
private fun NameSlot(label: State<String>, color: Color) {
    Text(
        text = label.value,
        color = color,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = SafeX, top = 18.dp, end = SafeX),
    )
}

/** A square tile: the icon or banner cropped square on the flat tile colour. */
@Composable
private fun SquareTile(
    app: AppEntry,
    palette: SwitchPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The bounce: a tween on scale only, read in the layer so it redraws
    // this tile and recomposes nothing.
    val scale by animateFloatAsState(if (focused) BOUNCE_SCALE else 1f, animationSpec = tween(BOUNCE_MS), label = "bounce")
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interaction,
        modifier = modifier
            .size(TileSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape = TileShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.tile,
            focusedContainerColor = palette.tile,
            pressedContainerColor = palette.tile,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Cyan), shape = TileShape),
        ),
    ) {
        val image = app.image
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(TileShape),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = app.label.take(2).uppercase(), color = palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** A small round button with the entry's initials; its full name shows in the name slot while focused. */
@Composable
private fun RoundButton(button: Button, palette: SwitchPalette, onFocused: () -> Unit, onClick: () -> Unit) {
    val initials = remember(button.label) {
        if (button is Button.Variant) "LD" else shortCode(button.label)
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.size(46.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = palette.tile,
            focusedContainerColor = Cyan,
            pressedContainerColor = Cyan,
            contentColor = palette.text,
            focusedContentColor = Color(0xFF2D2D2D),
            pressedContentColor = Color(0xFF2D2D2D),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Cyan), shape = CircleShape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = initials, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = button.label })
        }
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
    Text(text = timeFormat.format(now), color = color, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}
