package la.bsmith.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What every theme gets: the lists, the settings, and the actions. A theme is
 * one composable that draws the whole home screen from this; adding a look
 * is one new file and a line in [LauncherApp].
 */
class LauncherShared(
    val apps: List<AppEntry>,
    val favorites: List<AppEntry>,
    val hiddenApps: List<AppEntry>,
    val favoriteIds: Set<String>,
    val settings: List<SystemEntry>,
    val theme: Theme,
    /** The current theme's own sub-setting, "" when it has none set. */
    val variant: String,
    val backdropStyle: BackdropStyle,
    val open: (AppEntry) -> Unit,
    val openSetting: (SystemEntry) -> Unit,
    /** Adds the app to favorites, or removes it; a toast says which. */
    val toggleFavorite: (AppEntry) -> Unit,
    val hide: (AppEntry) -> Unit,
    val unhide: (AppEntry) -> Unit,
    /** Opens the system's uninstall dialog; does nothing for a system package. */
    val uninstall: (AppEntry) -> Unit,
    val setTheme: (Theme) -> Unit,
    val setVariant: (String) -> Unit,
    val setBackdropStyle: (BackdropStyle) -> Unit,
)

@Composable
fun LauncherApp() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val repository = remember { AppRepository(context) }
    val store = remember { LauncherStore(context) }
    val tvRestart = remember { TvRestart(context) }
    val scope = rememberCoroutineScope()

    // Null until loaded: a theme is drawn only once every list is in, so
    // that no tile that appears first takes the initial focus.
    var allApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var settings by remember { mutableStateOf<List<SystemEntry>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val favoriteIds by store.favorites.collectAsStateWithLifecycle(initialValue = null)
    val hiddenIds by store.hidden.collectAsStateWithLifecycle(initialValue = null)
    val backdropStyle by store.backdropStyle.collectAsStateWithLifecycle(initialValue = BackdropStyle.STATIC)
    val theme by store.theme.collectAsStateWithLifecycle(initialValue = null)
    val variants by store.variants.collectAsStateWithLifecycle(initialValue = emptyMap())

    // Reload the app list when the launcher comes back and when apps come or
    // go. A package that is gone for good also leaves favorites and the hide
    // list, so no stale entry lingers.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val packageName = i?.data?.schemeSpecificPart
                if (i?.action == Intent.ACTION_PACKAGE_REMOVED && packageName != null && !i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    scope.launch { store.forgetPackage(packageName) }
                }
                reload++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            val loaded = repository.loadApps()
            val sections = repository.settingsEntries()
            withContext(Dispatchers.Main) {
                allApps = loaded
                settings = sections
            }
        }
    }

    val currentTheme = theme
    val loaded = allApps != null && favoriteIds != null && hiddenIds != null && currentTheme != null
    val apps = remember(allApps, hiddenIds) { allApps.orEmpty().filterNot { it.id in hiddenIds.orEmpty() } }
    val hiddenApps = remember(allApps, hiddenIds) { allApps.orEmpty().filter { it.id in hiddenIds.orEmpty() } }
    val favorites = remember(apps, favoriteIds) {
        favoriteIds.orEmpty().mapNotNull { id -> apps.firstOrNull { it.id == id } }
    }

    fun toast(text: String, length: Int = Toast.LENGTH_SHORT) = Toast.makeText(context, text, length).show()

    fun restart() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                tvRestart.restart(onPrompt = { scope.launch { toast(resources.getString(R.string.restart_allow), Toast.LENGTH_LONG) } })
            }
            val message = when (result) {
                TvRestart.Result.RESTARTING -> return@launch
                TvRestart.Result.NO_ADB -> R.string.restart_no_adb
                TvRestart.Result.NOT_ALLOWED -> R.string.restart_not_allowed
                TvRestart.Result.FAILED -> R.string.restart_failed
            }
            toast(resources.getString(message), Toast.LENGTH_LONG)
        }
    }

    val shared = remember(apps, favorites, hiddenApps, favoriteIds, settings, currentTheme, variants, backdropStyle) {
        LauncherShared(
            apps = apps,
            favorites = favorites,
            hiddenApps = hiddenApps,
            favoriteIds = favoriteIds.orEmpty().toSet(),
            settings = settings,
            theme = currentTheme ?: Theme.DAPPLE_TV,
            variant = variants[currentTheme] ?: "",
            backdropStyle = backdropStyle,
            open = { entry ->
                if (!repository.launch(entry)) toast(resources.getString(R.string.cannot_launch, entry.label))
            },
            openSetting = { entry ->
                when (val action = entry.action) {
                    is SystemAction.Open ->
                        if (!repository.launch(action)) toast(resources.getString(R.string.cannot_launch, entry.label))
                    SystemAction.Restart -> restart()
                }
            },
            toggleFavorite = { entry ->
                scope.launch {
                    val added = store.toggleFavorite(entry.id)
                    val message = if (added) R.string.added_favorite else R.string.removed_favorite
                    toast(resources.getString(message, entry.label))
                }
            },
            hide = { entry ->
                scope.launch {
                    store.hide(entry.id)
                    toast(resources.getString(R.string.hid_app, entry.label))
                }
            },
            unhide = { entry ->
                scope.launch {
                    store.unhide(entry.id)
                    toast(resources.getString(R.string.unhid_app, entry.label))
                }
            },
            uninstall = { entry ->
                if (!repository.uninstall(entry)) toast(resources.getString(R.string.cannot_uninstall, entry.label))
            },
            setTheme = { next -> scope.launch { store.setTheme(next) } },
            setVariant = { value -> currentTheme?.let { t -> scope.launch { store.setVariant(t, value) } } },
            setBackdropStyle = { style -> scope.launch { store.setBackdropStyle(style) } },
        )
    }

    if (!loaded) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)))
        return
    }
    Box(modifier = Modifier.fillMaxSize().exposeTestTags()) {
        shared.theme.content(shared)
    }
}
