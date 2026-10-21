package la.bsmith.adless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CardShape = RoundedCornerShape(10.dp)
private val PanelShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)
private val PanelColor = Color(0xF2131B2C)

// The TV safe area: 5% of a 960 x 540 dp screen.
private val SafeX = 48.dp
private val SafeY = 27.dp

// Six favorites across the safe width, with a 16 dp gap: (960 - 96 - 80) / 6.
private val FavoriteWidth = 130.dp
private val FavoriteGap = 16.dp
private val RowBottom = 36.dp

private const val BACKDROP_DEBOUNCE_MS = 150L
private const val APP_COLUMNS = 5
private const val SETTINGS_COLUMNS = 4
private const val BACKDROP_FADE_MS = 300

// The static tint's box, as fractions of the screen from the bottom left; the
// fade's radius is the smaller side.
private const val TINT_WIDTH = 0.55f
private const val TINT_HEIGHT = 0.75f

enum class Tab { Home, Apps, Settings }

/**
 * The Dapple TV look: a backdrop driven by the focused favorite, one favorites
 * row, tabs for Home, Apps and Settings. Owns its own UI state; the lists and
 * the actions come from [LauncherShared].
 */
@Composable
fun DappleTvTheme(shared: LauncherShared) {
    val context = LocalContext.current
    val apps = shared.apps
    val favorites = shared.favorites

    var tab by remember { mutableStateOf(Tab.Home) }

    // The popup for the long-pressed card, the Hidden apps list, and the card
    // to focus once a card has left the screen.
    var menuFor by remember { mutableStateOf<MenuTarget?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    // The favorite focused last, where Back and a tab switch put focus again.
    var lastFavorite by remember { mutableStateOf<String?>(null) }

    // Back returns to Home with the row focused; on Home it does nothing,
    // this being the bottom of the stack.
    BackHandler {
        if (tab != Tab.Home) {
            tab = Tab.Home
            focusTarget = FocusTarget(lastFavorite ?: ADD_TILE_ID, true)
        }
    }

    // Blurred backdrops and tints, one per favorite, made off the UI thread
    // at startup and kept; a focus change never computes anything.
    val backdrops = remember { mutableStateMapOf<String, ImageBitmap>() }
    val tints = remember { mutableStateMapOf<String, Color>() }
    LaunchedEffect(favorites) {
        for (app in favorites) {
            val image = app.image ?: continue
            if (app.id !in tints) tints[app.id] = withContext(Dispatchers.Default) { Color(Backdrops.tint(image)) }
            if (app.id !in backdrops) backdrops[app.id] = withContext(Dispatchers.Default) { Backdrops.blur(image) }
        }
    }
    val photo = rememberBackdropPhoto()

    /** The card beside [entry] in the row it is leaving, for focus to land on. */
    fun neighbour(entry: AppEntry, inFavorites: Boolean): FocusTarget? {
        val row = if (inFavorites) favorites else apps
        val index = row.indexOfFirst { it.id == entry.id }
        val next = row.getOrNull(index + 1) ?: row.getOrNull(index - 1)
        return next?.let { FocusTarget(it.id, inFavorites) }
    }

    fun toggleFavorite(target: MenuTarget) {
        val (entry, inFavorites) = target
        // Removing a favorite from its row takes the card away; the grid copy stays.
        if (inFavorites) {
            focusTarget = neighbour(entry, true) ?: FocusTarget(ADD_TILE_ID, true)
            lastFavorite = null
        }
        shared.toggleFavorite(entry)
    }

    fun hide(target: MenuTarget) {
        val (entry, inFavorites) = target
        focusTarget = neighbour(entry, inFavorites)
            ?: if (inFavorites) FocusTarget(ADD_TILE_ID, true)
            else apps.firstOrNull { it.id != entry.id }?.let { FocusTarget(it.id, false) }
        if (lastFavorite == entry.id) lastFavorite = null
        shared.hide(entry)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.fillMaxSize()) {
            LauncherContent(
                tab = tab,
                onTabChange = { tab = it },
                apps = apps,
                favorites = favorites,
                settings = shared.settings,
                backdrops = backdrops,
                tints = tints,
                backdropStyle = shared.backdropStyle,
                onBackdropStyleChange = shared.setBackdropStyle,
                currentTheme = shared.theme,
                onPickTheme = { showThemes = true },
                photo = photo,
                focusTarget = focusTarget,
                onFocusTargetDone = { focusTarget = null },
                onFavoriteFocused = { id -> if (id in shared.favoriteIds) lastFavorite = id },
                onOpen = shared.open,
                onLongPress = { entry, inFavorites -> menuFor = MenuTarget(entry, inFavorites) },
                onOpenSetting = shared.openSetting,
                onShowHidden = { showHidden = true },
                onAddFromApps = {
                    tab = Tab.Apps
                    apps.firstOrNull()?.let { focusTarget = FocusTarget(it.id, false) }
                },
            )
            menuFor?.let { target ->
                AppMenu(
                    app = target.entry,
                    isFavorite = target.entry.id in shared.favoriteIds,
                    onToggleFavorite = { toggleFavorite(target) },
                    onHide = { hide(target) },
                    onUninstall = { shared.uninstall(target.entry) },
                    onDismiss = { menuFor = null },
                )
            }
            if (showHidden) {
                HiddenAppsDialog(hidden = shared.hiddenApps, onUnhide = shared.unhide, onDismiss = { showHidden = false })
            }
            if (showThemes) {
                ThemePicker(current = shared.theme, onPick = shared.setTheme, onDismiss = { showThemes = false })
            }
        }
    }
}

/** The theme list: every registered look, the current one marked and focused. */
@Composable
private fun ThemePicker(current: Theme, onPick: (Theme) -> Unit, onDismiss: () -> Unit) {
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
                .width(380.dp)
                .background(PanelColor, PanelShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.theme_title),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            for (theme in Theme.entries) {
                TextTile(
                    label = if (theme == current) stringResource(R.string.theme_current, theme.label) else theme.label,
                    onClick = { onPick(theme); onDismiss() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(if (theme == current) Modifier.focusRequester(focusCurrent) else Modifier),
                )
            }
        }
    }
}

/** A long-pressed card: the app and whether the press came from the favorites row. */
private data class MenuTarget(val entry: AppEntry, val inFavorites: Boolean)

/** A card to move focus to, in the favorites row or the grid. */
private data class FocusTarget(val id: String, val inFavorites: Boolean)

/** The id of the "Add from Apps" tile that stands in for an empty favorites row. */
private const val ADD_TILE_ID = "add-from-apps"

@Composable
private fun LauncherContent(
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    apps: List<AppEntry>,
    favorites: List<AppEntry>,
    settings: List<SystemEntry>,
    backdrops: Map<String, ImageBitmap>,
    tints: Map<String, Color>,
    backdropStyle: BackdropStyle,
    onBackdropStyleChange: (BackdropStyle) -> Unit,
    currentTheme: Theme,
    onPickTheme: () -> Unit,
    photo: ImageBitmap?,
    focusTarget: FocusTarget?,
    onFocusTargetDone: () -> Unit,
    onFavoriteFocused: (String) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onLongPress: (AppEntry, Boolean) -> Unit,
    onOpenSetting: (SystemEntry) -> Unit,
    onShowHidden: () -> Unit,
    onAddFromApps: () -> Unit,
) {
    // The first favorite, else the Add tile, takes focus once it exists.
    // Later, a requested target does: the card beside one that was removed.
    var initialFocusDone by remember { mutableStateOf(false) }
    val target = focusTarget
        ?: if (!initialFocusDone) FocusTarget(favorites.firstOrNull()?.id ?: ADD_TILE_ID, true) else null
    val requester = remember { FocusRequester() }
    LaunchedEffect(target, apps, favorites, tab) {
        if (target == null) return@LaunchedEffect
        // Give the lazy row a frame to compose the item, and a dismissed
        // popup's window time to hand focus back.
        repeat(10) {
            delay(50)
            if (runCatching { requester.requestFocus() }.isSuccess) {
                initialFocusDone = true
                onFocusTargetDone()
                return@LaunchedEffect
            }
        }
    }
    fun focusIf(matches: Boolean) = if (matches) Modifier.focusRequester(requester) else Modifier

    // Up from the row or grid lands on the current tab, never a neighbour,
    // since a tab switches as soon as it is focused.
    val tabRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    // Set on each item, not on the row or grid: lazy items do not inherit it.
    val upToTab = tabRequesters.getValue(tab)

    // The favorite under focus. Held as a state object and read only by the
    // backdrop layer and the title, so a focus change recomposes those two
    // and nothing else: not the row, not the tabs.
    val focused = remember { mutableStateOf<String?>(null) }
    // The same value as a flow, written in the callback itself, so the
    // backdrop's debounce sees every change the moment it happens rather
    // than a frame later.
    val focusFlow = remember { MutableStateFlow<String?>(null) }
    val onFocusedChange = remember(onFavoriteFocused) {
        { id: String? ->
            focused.value = id
            focusFlow.value = id
            if (id != null) onFavoriteFocused(id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BackdropLayer(
            focused = focusFlow,
            active = tab == Tab.Home,
            style = backdropStyle,
            backdrops = backdrops,
            tints = tints,
            photo = photo,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(tab = tab, onTabChange = onTabChange, requesters = tabRequesters)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    Tab.Home -> HomeTab(
                        favorites = favorites,
                        focused = focused,
                        upToTab = upToTab,
                        focusIf = ::focusIf,
                        target = target,
                        onFocusedChange = onFocusedChange,
                        onOpen = onOpen,
                        onLongPress = { onLongPress(it, true) },
                        onAddFromApps = onAddFromApps,
                    )
                    Tab.Apps -> AppsTab(
                        apps = apps,
                        upToTab = upToTab,
                        focusIf = { app -> focusIf(target?.inFavorites == false && app.id == target.id) },
                        onOpen = onOpen,
                        onLongPress = { onLongPress(it, false) },
                    )
                    Tab.Settings -> SettingsTab(
                        settings = settings,
                        backdropStyle = backdropStyle,
                        upToTab = upToTab,
                        onOpenSetting = onOpenSetting,
                        onShowHidden = onShowHidden,
                        onBackdropStyleChange = onBackdropStyleChange,
                        currentTheme = currentTheme,
                        onPickTheme = onPickTheme,
                    )
                }
            }
        }
    }
}

/**
 * The backdrop. STATIC: one image (the photo, else the gradient) with a tint
 * in the focused favorite's colour fading in at the bottom left. BANNERS: the
 * focused favorite's blurred banner, crossfaded. Focus changes are coalesced:
 * a new one within the debounce cancels the pending swap, so a fast scroll
 * across four tiles swaps once.
 */
@Composable
private fun BackdropLayer(
    focused: StateFlow<String?>,
    active: Boolean,
    style: BackdropStyle,
    backdrops: Map<String, ImageBitmap>,
    tints: Map<String, Color>,
    photo: ImageBitmap?,
) {
    var backdropId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) {
        focused.collectLatest { id ->
            val next = if (active) id else null
            if (next != backdropId) {
                delay(BACKDROP_DEBOUNCE_MS)
                backdropId = next
            }
        }
    }
    when (style) {
        BackdropStyle.STATIC -> Box(modifier = Modifier.fillMaxSize()) {
            FallbackBackdrop(photo)
            // A quarter circle of the focused favorite's colour in the
            // bottom-left corner, fading to nothing before the box's edges
            // so it has no edge of its own. The colour changes in one step
            // at the debounced swap: animating it would redraw the box every
            // frame, which this GPU cannot do at 60 Hz. The colour is read in
            // the draw lambda, so a change redraws the box and recomposes
            // nothing.
            val tint = backdropId?.let { tints[it] } ?: Color.Transparent
            Box(
                modifier = Modifier
                    .fillMaxWidth(TINT_WIDTH)
                    .fillMaxHeight(TINT_HEIGHT)
                    .align(Alignment.BottomStart)
                    .drawBehind {
                        if (tint.alpha > 0.01f) {
                            drawRect(
                                Brush.radialGradient(
                                    colors = listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.2f), Color.Transparent),
                                    center = Offset(0f, size.height),
                                    radius = minOf(size.width, size.height),
                                ),
                            )
                        }
                    },
            )
        }
        BackdropStyle.BANNERS -> {
            Crossfade(targetState = backdropId, animationSpec = tween(BACKDROP_FADE_MS), label = "backdrop") { id ->
                val blurred = id?.let { backdrops[it] }
                if (blurred != null) FeaturedBackdrop(blurred) else FallbackBackdrop(photo)
            }
        }
    }
}

/** The three tabs centred, the clock right, all in the safe area; no wordmark. */
@Composable
private fun TopBar(tab: Tab, onTabChange: (Tab) -> Unit, requesters: Map<Tab, FocusRequester>) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SafeX, end = SafeX, top = SafeY),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center).focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in Tab.entries) {
                TabPill(
                    label = stringResource(
                        when (t) {
                            Tab.Home -> R.string.tab_home
                            Tab.Apps -> R.string.tab_apps
                            Tab.Settings -> R.string.tab_settings
                        },
                    ),
                    selected = t == tab,
                    onSelect = { onTabChange(t) },
                    modifier = Modifier.testTag("tab_" + t.name.lowercase()).focusRequester(requesters.getValue(t)),
                )
            }
        }
        Clock(modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** A tab: filled when current, outlined when focused. Focus alone selects it. */
@Composable
private fun TabPill(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onSelect,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onSelect() }
            .height(36.dp),
        shape = ClickableSurfaceDefaults.shape(shape = PillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.92f) else Color.Transparent,
            focusedContainerColor = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
            contentColor = if (selected) Color.Black else Color.White,
            focusedContentColor = if (selected) Color.Black else Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = PillShape),
        ),
    ) {
        // Sized by the label: a fill here would take the whole bar.
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The Home tab: the focused favorite's name over the backdrop, the row at the bottom. */
@Composable
private fun HomeTab(
    favorites: List<AppEntry>,
    focused: MutableState<String?>,
    upToTab: FocusRequester,
    focusIf: (Boolean) -> Modifier,
    target: FocusTarget?,
    onFocusedChange: (String?) -> Unit,
    onOpen: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
    onAddFromApps: () -> Unit,
) {
    val rowHeight = FavoriteWidth * 9 / 16 + 28.dp
    Box(modifier = Modifier.fillMaxSize()) {
        FeaturedTitle(
            favorites = favorites,
            focused = focused,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = SafeX, end = 320.dp, bottom = RowBottom + rowHeight + 28.dp),
        )
        Text(
            text = stringResource(R.string.hold_ok_to_edit),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = SafeX, bottom = RowBottom + rowHeight + 10.dp),
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = RowBottom)) {
            if (favorites.isEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = SafeX)) {
                    TextTile(
                        label = stringResource(R.string.add_from_apps),
                        onClick = onAddFromApps,
                        modifier = Modifier
                            .width(FavoriteWidth)
                            .aspectRatio(16f / 9f)
                            .focusProperties { up = upToTab }
                            .then(focusIf(target?.inFavorites == true && target.id == ADD_TILE_ID)),
                    )
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(FavoriteGap),
                    contentPadding = PaddingValues(horizontal = SafeX, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().testTag(Tags.FAVORITES).focusRestorer(),
                ) {
                    itemsIndexed(favorites, key = { _, app -> app.id }) { index, app ->
                        // The row's ends go nowhere: a press past the last
                        // card would otherwise reach a tab pill above it and
                        // switch tabs.
                        val first = index == 0
                        val last = index == favorites.lastIndex
                        Column(
                            modifier = Modifier
                                .width(FavoriteWidth)
                                .focusProperties {
                                    up = upToTab
                                    if (first) left = FocusRequester.Cancel
                                    if (last) right = FocusRequester.Cancel
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppCard(
                                app = app,
                                onClick = { onOpen(app) },
                                onLongClick = { onLongPress(app) },
                                focusedScale = 1.1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(Tags.TILE)
                                    .onFocusChanged { state ->
                                        // Reads the state in the callback, not in composition.
                                        if (state.isFocused) onFocusedChange(app.id)
                                        else if (focused.value == app.id) onFocusedChange(null)
                                    }
                                    .then(focusIf(target?.inFavorites == true && app.id == target.id)),
                            )
                            Text(
                                text = app.label,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The focused favorite's name and tagline; the only part of the tab that reads the focus. */
@Composable
private fun FeaturedTitle(favorites: List<AppEntry>, focused: State<String?>, modifier: Modifier) {
    val featured by remember(favorites) { derivedStateOf { favorites.firstOrNull { it.id == focused.value } } }
    val app = featured ?: return
    Column(modifier = modifier) {
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Taglines.of(app.packageName)?.let { line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The Apps tab: every visible app, five across. */
@Composable
private fun AppsTab(
    apps: List<AppEntry>,
    upToTab: FocusRequester,
    focusIf: (AppEntry) -> Modifier,
    onOpen: (AppEntry) -> Unit,
    onLongPress: (AppEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(APP_COLUMNS),
        modifier = Modifier.fillMaxSize().testTag(Tags.APPS),
        contentPadding = PaddingValues(start = SafeX, end = SafeX, top = 24.dp, bottom = SafeY),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
            AppCard(
                app = app,
                onClick = { onOpen(app) },
                onLongClick = { onLongPress(app) },
                // Only the top row hands Up to the tab; lower rows move within the grid.
                modifier = focusIf(app).testTag(Tags.TILE).then(if (index < APP_COLUMNS) Modifier.focusProperties { up = upToTab } else Modifier),
            )
        }
    }
}

/** The Settings tab: Fire TV's settings screens, and the hidden apps list. */
@Composable
private fun SettingsTab(
    settings: List<SystemEntry>,
    backdropStyle: BackdropStyle,
    upToTab: FocusRequester,
    onOpenSetting: (SystemEntry) -> Unit,
    onShowHidden: () -> Unit,
    onBackdropStyleChange: (BackdropStyle) -> Unit,
    currentTheme: Theme,
    onPickTheme: () -> Unit,
) {
    fun topRow(index: Int) = if (index < SETTINGS_COLUMNS) Modifier.focusProperties { up = upToTab } else Modifier
    LazyVerticalGrid(
        columns = GridCells.Fixed(SETTINGS_COLUMNS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = SafeX, end = SafeX, top = 24.dp, bottom = SafeY),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        gridItemsIndexed(settings, key = { _, entry -> entry.label }) { index, entry ->
            TextTile(label = entry.label, onClick = { onOpenSetting(entry) }, modifier = Modifier.fillMaxWidth().height(64.dp).then(topRow(index)))
        }
        item(key = "hidden") {
            TextTile(label = stringResource(R.string.hidden_apps), onClick = onShowHidden, modifier = Modifier.fillMaxWidth().height(64.dp))
        }
        item(key = "backdrop") {
            val label = stringResource(
                if (backdropStyle == BackdropStyle.STATIC) R.string.backdrop_static else R.string.backdrop_banners,
            )
            TextTile(
                label = label,
                onClick = {
                    onBackdropStyleChange(
                        if (backdropStyle == BackdropStyle.STATIC) BackdropStyle.BANNERS else BackdropStyle.STATIC,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
        item(key = "theme") {
            TextTile(
                label = stringResource(R.string.theme_tile, currentTheme.label),
                onClick = onPickTheme,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )
        }
    }
}

/** The long-press popup: favorite on or off, and hide. Back closes it. */
@Composable
private fun AppMenu(
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
                .width(380.dp)
                .background(PanelColor, PanelShape)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            TextTile(
                label = stringResource(if (isFavorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                onClick = { onToggleFavorite(); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(first),
            )
            TextTile(
                label = stringResource(R.string.hide_app),
                onClick = { onHide(); onDismiss() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            )
            // A built-in package cannot go; hiding is the way to remove it from view.
            if (!app.isSystem) {
                TextTile(
                    label = stringResource(R.string.uninstall),
                    onClick = { onUninstall(); onDismiss() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                )
            }
        }
    }
}

/** The hidden apps, one per row; OK on a row returns it to the grid. */
@Composable
private fun HiddenAppsDialog(
    hidden: List<AppEntry>,
    onUnhide: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The focused row, or the Close button when there is none.
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
                .width(600.dp)
                .heightIn(max = 640.dp)
                .background(PanelColor, PanelShape)
                .padding(24.dp),
        ) {
            Text(text = stringResource(R.string.hidden_apps), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(if (hidden.isEmpty()) R.string.no_hidden_apps else R.string.unhide_hint),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(hidden, key = { it.id }) { app ->
                    HiddenRow(
                        app = app,
                        onClick = {
                            val index = hidden.indexOfFirst { it.id == app.id }
                            focusId = (hidden.getOrNull(index + 1) ?: hidden.getOrNull(index - 1))?.id
                            onUnhide(app)
                        },
                        modifier = if (app.id == focusId) Modifier.focusRequester(requester) else Modifier,
                    )
                }
            }
            TextTile(
                label = stringResource(R.string.close),
                onClick = onDismiss,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .width(150.dp)
                    .height(48.dp)
                    .then(if (focusId == null) Modifier.focusRequester(requester) else Modifier),
            )
        }
    }
}

@Composable
private fun HiddenRow(app: AppEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val image = app.image
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = if (app.isBanner) 64.dp else 36.dp, height = 36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
            Text(text = app.label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppCard(
    app: AppEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.08f,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
            pressedContainerColor = Color.White.copy(alpha = 0.30f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale, pressedScale = 1.0f),
        // A border and a small scale only: a glow is a shadow, which this GPU pays for every frame.
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        val image = app.image
        if (image != null && app.isBanner) {
            Image(
                bitmap = image,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CardShape),
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (image != null) {
                    Image(bitmap = image, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TextTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(150.dp).height(56.dp),
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = CardShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text = label, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = clockNow()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    Text(
        text = timeFormat.format(now),
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** The focused favorite's banner, blurred and scaled to the screen, darkened most at the bottom left. */
@Composable
private fun FeaturedBackdrop(image: ImageBitmap) {
    Box(modifier = Modifier.fillMaxSize()) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // One gradient, heaviest at the bottom left where the title sits.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Black.copy(alpha = 0.18f)),
                        start = Offset(0f, Float.POSITIVE_INFINITY),
                        end = Offset(Float.POSITIVE_INFINITY, 0f),
                    ),
                ),
        )
    }
}

/** The user's photo if there is one, else a dark gradient, with a scrim for legibility. */
@Composable
private fun FallbackBackdrop(photo: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(Color(0xFF0B1220), Color(0xFF1B2A4A), Color(0xFF060910)))),
    ) {
        if (photo != null) {
            Image(bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.5f)))),
        )
    }
}

/**
 * The photo at backdrop.jpg or backdrop.png in the app's external files
 * directory (pushed over adb), loaded once off the UI thread.
 */
@Composable
fun rememberBackdropPhoto(): ImageBitmap? {
    val context = LocalContext.current
    var photo by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) {
        if (DeviceTest.freezeBackdrop) return@LaunchedEffect
        photo = withContext(Dispatchers.IO) { loadBackdrop(context) }
    }
    return photo
}

fun loadBackdrop(context: Context): ImageBitmap? {
    val dir = context.getExternalFilesDir(null) ?: return null
    val file = listOf("backdrop.jpg", "backdrop.png").map { File(dir, it) }.firstOrNull { it.exists() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}
