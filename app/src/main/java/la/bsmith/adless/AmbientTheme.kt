package la.bsmith.adless

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.delay

private val CardShape = RoundedCornerShape(8.dp)
private val SafeX = 48.dp
private val SafeY = 27.dp
private val TileWidth = 150.dp
private const val IDLE_MS = 8_000L
private const val FADE_MS = 200
private const val GRID_COLUMNS = 5

private sealed interface GridTile {
    val label: String

    data class App(val entry: AppEntry) : GridTile {
        override val label get() = entry.label
    }

    data class Setting(val entry: SystemEntry) : GridTile {
        override val label get() = entry.label
    }

    data object Hidden : GridTile {
        override val label get() = "Hidden apps"
    }

    data object ThemePick : GridTile {
        override val label get() = "Theme"
    }
}

/**
 * The Ambient clock look: a still backdrop and a large clock, nothing else
 * until a key is pressed. Then the favorites row fades in at the bottom and
 * takes focus; Down from it opens the app grid over a darkened backdrop;
 * eight seconds without input and the clock is alone again. The clock
 * updates once a minute and nothing else recomposes while idle.
 */
@Composable
fun AmbientTheme(shared: LauncherShared) {
    val photo = rememberBackdropPhoto()
    val gridTiles = remember(shared.apps, shared.settings) {
        shared.apps.map<AppEntry, GridTile> { GridTile.App(it) } +
            shared.settings.map { GridTile.Setting(it) } + GridTile.Hidden + GridTile.ThemePick
    }

    var awake by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(0L) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }

    val idleFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }

    suspend fun focusSoon(requester: FocusRequester) {
        repeat(10) {
            delay(50)
            if (runCatching { requester.requestFocus() }.isSuccess) return
        }
    }

    // Idle holds focus on the root so the first key press can be seen.
    LaunchedEffect(awake, showGrid) {
        when {
            !awake -> focusSoon(idleFocus)
            showGrid -> focusSoon(gridFocus)
            else -> focusSoon(rowFocus)
        }
    }
    // The idle timer: any input restarts it; when it runs out, the clock is alone.
    LaunchedEffect(awake, lastInput) {
        if (!awake) return@LaunchedEffect
        delay(IDLE_MS)
        showGrid = false
        awake = false
    }
    // Back: the grid closes, then the row goes, then nothing.
    BackHandler {
        when {
            showGrid -> { showGrid = false; lastInput = System.currentTimeMillis() }
            awake -> awake = false
        }
    }

    val menuStyle = remember {
        MenuStyle(
            panel = Color(0xF2141820), text = Color.White, dim = Color.White.copy(alpha = 0.6f),
            focusBackground = Color.White, focusText = Color.Black,
            shape = RoundedCornerShape(12.dp), rowShape = CardShape,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(idleFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val dpad = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                    event.key == Key.DirectionLeft || event.key == Key.DirectionRight || event.key == Key.DirectionCenter
                if (!dpad) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()
                if (!awake) {
                    awake = true
                    true
                } else {
                    false
                }
            },
    ) {
        Backdrop(photo)
        ClockFace()

        AnimatedVisibility(
            visible = awake && !showGrid,
            enter = fadeIn(tween(FADE_MS)),
            exit = fadeOut(tween(FADE_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FavoritesRow(
                favorites = shared.favorites,
                firstFocus = rowFocus,
                onOpen = shared.open,
                onLongPress = { menuFor = it },
                onDown = { showGrid = true },
            )
        }

        if (showGrid) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f))) {
                AppGrid(
                    tiles = gridTiles,
                    firstFocus = gridFocus,
                    onOpen = shared.open,
                    onLongPress = { menuFor = it },
                    onSetting = shared.openSetting,
                    onHidden = { showHidden = true },
                    onThemes = { showThemes = true },
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
            onDismiss = { menuFor = null; lastInput = System.currentTimeMillis() },
        )
    }
    if (showHidden) StyledHiddenList(menuStyle, shared.hiddenApps, shared.unhide) { showHidden = false; lastInput = System.currentTimeMillis() }
    if (showThemes) StyledThemePicker(menuStyle, shared.theme, shared.setTheme) { showThemes = false; lastInput = System.currentTimeMillis() }
}

/** The photo if there is one, else a dark gradient. Drawn once. */
@Composable
private fun Backdrop(photo: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0B1220), Color(0xFF1B2A4A), Color(0xFF060910)))),
    ) {
        if (photo != null) {
            Image(bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }
    }
}

/** The clock and the date, centred; the only thing that changes while idle, once a minute. */
@Composable
private fun ClockFace() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockNow()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val dateFormat = remember { SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text = timeFormat.format(now), color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Light)
        Text(text = dateFormat.format(now), color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
    }
}

@Composable
private fun FavoritesRow(
    favorites: List<AppEntry>,
    firstFocus: FocusRequester,
    onOpen: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onDown: () -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = SafeX, end = SafeX, top = 12.dp, bottom = SafeY + 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(Tags.FAVORITES)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onDown()
                    true
                } else {
                    false
                }
            },
    ) {
        itemsIndexed(favorites, key = { _, app -> app.id }) { index, app ->
            Tile(
                app = app,
                onClick = { onOpen(app) },
                onLongClick = { onLongPress(app) },
                modifier = Modifier
                    .width(TileWidth)
                    .testTag(Tags.TILE)
                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    .focusProperties {
                        if (index == 0) left = FocusRequester.Cancel
                        if (index == favorites.lastIndex) right = FocusRequester.Cancel
                    },
            )
        }
    }
}

@Composable
private fun AppGrid(
    tiles: List<GridTile>,
    firstFocus: FocusRequester,
    onOpen: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onSetting: (SystemEntry) -> Unit,
    onHidden: () -> Unit,
    onThemes: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.fillMaxSize().testTag(Tags.APPS),
        contentPadding = PaddingValues(horizontal = SafeX, vertical = SafeY),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(tiles, key = { index, _ -> index }) { index, tile ->
            val focusMod = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
            when (tile) {
                is GridTile.App -> Tile(
                    app = tile.entry,
                    onClick = { onOpen(tile.entry) },
                    onLongClick = { onLongPress(tile.entry) },
                    modifier = focusMod.testTag(Tags.TILE),
                )
                else -> TextTile(
                    label = tile.label,
                    onClick = {
                        when (tile) {
                            is GridTile.Setting -> onSetting(tile.entry)
                            GridTile.Hidden -> onHidden()
                            GridTile.ThemePick -> onThemes()
                            is GridTile.App -> Unit
                        }
                    },
                    modifier = focusMod,
                )
            }
        }
    }
}

/** A 16:9 banner tile with a white border when focused; no scale, no glow. */
@Composable
private fun Tile(app: AppEntry, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.2f),
            pressedContainerColor = Color.White.copy(alpha = 0.2f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        val image = app.image
        if (image != null && app.isBanner) {
            Image(bitmap = image, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CardShape))
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Text(text = app.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TextTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White.copy(alpha = 0.2f),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
