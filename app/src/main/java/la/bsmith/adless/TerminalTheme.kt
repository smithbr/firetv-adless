package la.bsmith.adless

import android.graphics.Bitmap
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// One phosphor on near black; the dim shade is for labels and hints.
private val Phosphor = Color(0xFF8CF59A)
private val Screen = Color(0xFF050805)
private val Dim = Color(0xFF8CF59A).copy(alpha = 0.55f)
private val Mono = FontFamily.Monospace
private val TextSize = 17.sp
private val LineHeight = 30.dp
private val SafeX = 48.dp
private val SafeY = 27.dp

/** One line of the list: a section label, or something the cursor can rest on. */
private sealed interface Line {
    val key: String

    data class Label(val text: String) : Line {
        override val key get() = "label:$text"
    }

    data class App(val entry: AppEntry, val inFavorites: Boolean) : Line {
        override val key get() = (if (inFavorites) "fav:" else "app:") + entry.id
    }

    data class Setting(val entry: SystemEntry) : Line {
        override val key get() = "setting:${entry.label}"
    }

    data object Hidden : Line {
        override val key get() = "hidden"
    }

    data object ThemeSwitch : Line {
        override val key get() = "theme"
    }
}

/**
 * The Terminal look: one list of uppercase names under FAVORITES, APPS and
 * SETTINGS, a block cursor on the focused line, a fixed header and footer.
 * No images, no gradients, no shadows. Up and down move the cursor, Back
 * returns to the top, OK opens, holding OK edits.
 */
@Composable
fun TerminalTheme(shared: LauncherShared) {
    val lines = remember(shared.favorites, shared.apps, shared.settings) {
        buildList {
            if (shared.favorites.isNotEmpty()) {
                add(Line.Label("FAVORITES"))
                shared.favorites.forEach { add(Line.App(it, inFavorites = true)) }
            }
            add(Line.Label("APPS"))
            shared.apps.forEach { add(Line.App(it, inFavorites = false)) }
            add(Line.Label("SETTINGS"))
            shared.settings.forEach { add(Line.Setting(it)) }
            add(Line.Hidden)
            add(Line.ThemeSwitch)
        }
    }
    val firstEntry = lines.indexOfFirst { it !is Line.Label }

    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    // The cursor's underscore blinks at 1 Hz; only the focused line reads it.
    var blinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (DeviceTest.clockMillis != null) return@LaunchedEffect
        while (true) {
            delay(BLINK_MS)
            blinkOn = !blinkOn
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val topRequester = remember { FocusRequester() }
    fun focusTop() {
        scope.launch {
            listState.scrollToItem(0)
            repeat(10) {
                delay(50)
                if (runCatching { topRequester.requestFocus() }.isSuccess) return@launch
            }
        }
    }
    var initialFocusDone by remember { mutableStateOf(false) }
    LaunchedEffect(firstEntry) {
        if (!initialFocusDone && firstEntry >= 0) {
            initialFocusDone = true
            focusTop()
        }
    }
    BackHandler { focusTop() }

    val scanlines = rememberScanlines()

    Box(modifier = Modifier.fillMaxSize().background(Screen)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = SafeX, vertical = SafeY)) {
            Header()
            Rule()
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag(Tags.APPS)) {
                itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                    val focusMod = if (index == firstEntry) Modifier.focusRequester(topRequester) else Modifier
                    when (line) {
                        is Line.Label -> LabelLine(line.text)
                        is Line.App -> EntryLine(
                            text = line.entry.label.uppercase(),
                            blinkOn = blinkOn,
                            onClick = { shared.open(line.entry) },
                            onLongClick = { menuFor = line.entry },
                            modifier = focusMod.testTag(Tags.TILE),
                        )
                        is Line.Setting -> EntryLine(
                            text = line.entry.label.uppercase(),
                            blinkOn = blinkOn,
                            onClick = { shared.openSetting(line.entry) },
                            modifier = focusMod,
                        )
                        Line.Hidden -> EntryLine(
                            text = "HIDDEN APPS",
                            blinkOn = blinkOn,
                            onClick = { showHidden = true },
                            modifier = focusMod,
                        )
                        Line.ThemeSwitch -> EntryLine(
                            text = "THEME: ${shared.theme.label.uppercase()}",
                            blinkOn = blinkOn,
                            onClick = { showThemes = true },
                            modifier = focusMod,
                        )
                    }
                }
            }
            Rule()
            Mono("OK OPEN   HOLD OK EDIT   BACK TOP", color = Dim)
        }
        // Scanlines: a 1 by 3 pixel bitmap tiled over the screen, drawn once.
        // Measured: neither this nor the blink moves the 90th percentile
        // beyond the run-to-run noise (about 5 ms).
        Box(modifier = Modifier.fillMaxSize().background(scanlines))
    }

    menuFor?.let { app ->
        TerminalMenu(
            app = app,
            isFavorite = app.id in shared.favoriteIds,
            onToggleFavorite = { shared.toggleFavorite(app) },
            onHide = { shared.hide(app) },
            onUninstall = { shared.uninstall(app) },
            onDismiss = { menuFor = null },
        )
    }
    if (showHidden) {
        TerminalHidden(hidden = shared.hiddenApps, onUnhide = shared.unhide, onDismiss = { showHidden = false })
    }
    if (showThemes) {
        TerminalThemes(current = shared.theme, onPick = shared.setTheme, onDismiss = { showThemes = false })
    }
}

/** The theme list as a text menu: every registered look, the current one marked and focused. */
@Composable
private fun TerminalThemes(current: Theme, onPick: (Theme) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val focusCurrent = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { focusCurrent.requestFocus() }
        }
        Column(
            modifier = Modifier
                .exposeTestTags()
                .ignoreHeldSelect()
                .width(560.dp)
                .background(Screen)
                .border(2.dp, Phosphor)
                .padding(20.dp),
        ) {
            Mono("$ THEME", weight = FontWeight.Bold, modifier = Modifier.height(LineHeight))
            Mono("OK APPLIES. BACK CLOSES.", color = Dim, modifier = Modifier.height(LineHeight))
            Rule()
            for (theme in Theme.entries) {
                EntryLine(
                    text = theme.label.uppercase() + if (theme == current) "  [*]" else "  [ ]",
                    blinkOn = false,
                    onClick = { onPick(theme); onDismiss() },
                    modifier = if (theme == current) Modifier.focusRequester(focusCurrent) else Modifier,
                )
            }
        }
    }
}

private const val BLINK_MS = 500L

@Composable
private fun Header() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockNow()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    // Only the clock, right-aligned; no name or tag on the first line.
    Row(modifier = Modifier.fillMaxWidth().height(LineHeight), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.weight(1f))
        Mono(timeFormat.format(now).uppercase())
    }
}

/** A line of dashes across the width; the text is longer than any screen and clipped. */
@Composable
private fun Rule() {
    Mono("-".repeat(240), color = Dim, modifier = Modifier.fillMaxWidth().height(LineHeight))
}

@Composable
private fun LabelLine(text: String) {
    Mono(text, color = Dim, modifier = Modifier.fillMaxWidth().height(LineHeight).padding(top = 6.dp))
}

/**
 * A line the cursor can rest on. Focused, it is drawn inverted with a "> "
 * prompt and a blinking underscore; left and right go nowhere.
 */
@Composable
private fun EntryLine(
    text: String,
    blinkOn: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(LineHeight)
            .focusProperties {
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            },
        shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Phosphor,
            pressedContainerColor = Phosphor,
            contentColor = Phosphor,
            focusedContentColor = Screen,
            pressedContentColor = Screen,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        border = ClickableSurfaceDefaults.border(),
    ) {
        val cursor = if (focused && blinkOn) "_" else ""
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Mono(text = (if (focused) "> " else "  ") + text + cursor, color = if (focused) Screen else Phosphor)
        }
    }
}

@Composable
private fun Mono(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Phosphor,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        color = color,
        fontFamily = Mono,
        fontSize = TextSize,
        fontWeight = weight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}

/** The long-press menu: the same actions as the other look, as lines. */
@Composable
private fun TerminalMenu(
    app: AppEntry,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onHide: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { first.requestFocus() }
        }
        Column(
            modifier = Modifier
                .exposeTestTags()
                .testTag(Tags.APP_MENU)
                .ignoreHeldSelect()
                .width(560.dp)
                .background(Screen)
                .border(2.dp, Phosphor)
                .padding(20.dp),
        ) {
            Mono("$ ${app.label.uppercase()}", weight = FontWeight.Bold, modifier = Modifier.height(LineHeight))
            Rule()
            EntryLine(
                text = if (isFavorite) "REMOVE FROM FAVORITES" else "ADD TO FAVORITES",
                blinkOn = false,
                onClick = { onToggleFavorite(); onDismiss() },
                modifier = Modifier.focusRequester(first),
            )
            EntryLine(text = "HIDE APP", blinkOn = false, onClick = { onHide(); onDismiss() })
            if (!app.isSystem) EntryLine(text = "UNINSTALL", blinkOn = false, onClick = { onUninstall(); onDismiss() })
            EntryLine(text = "CANCEL", blinkOn = false, onClick = onDismiss)
        }
    }
}

/** The hidden apps as lines; OK on one restores it. */
@Composable
private fun TerminalHidden(
    hidden: List<AppEntry>,
    onUnhide: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        var focusId by remember { mutableStateOf(hidden.firstOrNull()?.id) }
        val requester = remember { FocusRequester() }
        LaunchedEffect(focusId, hidden) {
            repeat(10) {
                delay(50)
                if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
        Column(
            modifier = Modifier
                .exposeTestTags()
                .testTag(Tags.HIDDEN)
                .width(560.dp)
                .heightIn(max = 600.dp)
                .background(Screen)
                .border(2.dp, Phosphor)
                .padding(20.dp),
        ) {
            Mono("$ HIDDEN APPS", weight = FontWeight.Bold, modifier = Modifier.height(LineHeight))
            Mono(if (hidden.isEmpty()) "NONE. HOLD OK ON AN APP TO HIDE IT." else "OK RESTORES. BACK CLOSES.", color = Dim, modifier = Modifier.height(LineHeight))
            Rule()
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(hidden, key = { _, app -> app.id }) { _, app ->
                    EntryLine(
                        text = app.label.uppercase(),
                        blinkOn = false,
                        onClick = {
                            val index = hidden.indexOfFirst { it.id == app.id }
                            focusId = (hidden.getOrNull(index + 1) ?: hidden.getOrNull(index - 1))?.id
                            onUnhide(app)
                        },
                        modifier = if (app.id == focusId) Modifier.focusRequester(requester) else Modifier,
                    )
                }
            }
            EntryLine(
                text = "CLOSE",
                blinkOn = false,
                onClick = onDismiss,
                modifier = if (focusId == null) Modifier.focusRequester(requester) else Modifier,
            )
        }
    }
}

/**
 * A brush that tiles a 1 by 3 pixel bitmap with one faint dark row: the
 * scanlines. Made once; drawing it is one textured fill.
 */
@Composable
private fun rememberScanlines(): ShaderBrush = remember {
    val bitmap = Bitmap.createBitmap(1, 3, Bitmap.Config.ARGB_8888)
    bitmap.setPixel(0, 2, android.graphics.Color.argb(SCANLINE_ALPHA, 0, 0, 0))
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

private const val SCANLINE_ALPHA = 60
