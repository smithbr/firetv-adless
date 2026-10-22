package la.bsmith.launcher

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// The file keeps its original name so favorites saved by earlier builds survive.
private fun legacyNameOf(theme: Theme): String? = LegacyThemes.entries.firstOrNull { it.value == theme }?.key
private fun variantKey(theme: Theme) = stringPreferencesKey("variant.${theme.name}")

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

enum class BackdropStyle { STATIC, BANNERS }


/**
 * What the person has chosen on the TV: the favorites row, in order, and the
 * apps hidden from the grid. Both are lists of activity component names.
 */
class LauncherStore(private val context: Context) {
    private val favoritesKey = stringPreferencesKey("components")
    private val hiddenKey = stringPreferencesKey("hidden")
    private val backdropKey = stringPreferencesKey("backdrop")
    private val themeKey = stringPreferencesKey("theme")

    val favorites: Flow<List<String>> = context.dataStore.data.map { prefs -> decode(prefs[favoritesKey]) }
    val hidden: Flow<List<String>> = context.dataStore.data.map { prefs -> decode(prefs[hiddenKey]) }

    /** How the Home backdrop behaves: BANNERS swaps blurred app banners, STATIC keeps one image and tints it. */
    val backdropStyle: Flow<BackdropStyle> = context.dataStore.data.map { prefs ->
        BackdropStyle.entries.firstOrNull { it.name == prefs[backdropKey] } ?: BackdropStyle.STATIC
    }

    suspend fun setBackdropStyle(style: BackdropStyle) {
        context.dataStore.edit { prefs -> prefs[backdropKey] = style.name }
    }

    /** A theme's own sub-setting (Light or Dark, say), by theme; "" when unset. */
    val variants: Flow<Map<Theme, String>> = context.dataStore.data.map { prefs ->
        Theme.entries.associateWith { theme -> prefs[variantKey(theme)] ?: legacyNameOf(theme)?.let { prefs[stringPreferencesKey("variant.$it")] } ?: "" }
    }

    suspend fun setVariant(theme: Theme, value: String) {
        context.dataStore.edit { prefs -> prefs[variantKey(theme)] = value }
    }

    val theme: Flow<Theme> = context.dataStore.data.map { prefs ->
        Theme.byName(prefs[themeKey]) ?: Theme.DAPPLE_TV
    }

    suspend fun setTheme(theme: Theme) {
        context.dataStore.edit { prefs -> prefs[themeKey] = theme.name }
    }

    /** Adds the entry to favorites, or removes it when present. Returns true when it was added. */
    suspend fun toggleFavorite(id: String): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val current = decode(prefs[favoritesKey])
            added = id !in current
            prefs[favoritesKey] = encode(if (added) current + id else current - id)
        }
        return added
    }

    suspend fun hide(id: String) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[hiddenKey])
            if (id !in current) prefs[hiddenKey] = encode(current + id)
        }
    }

    /** Drops every favorite and hidden entry of a package that is gone. */
    suspend fun forgetPackage(packageName: String) {
        context.dataStore.edit { prefs ->
            prefs[favoritesKey] = encode(decode(prefs[favoritesKey]).filterNot { it.substringBefore('/') == packageName })
            prefs[hiddenKey] = encode(decode(prefs[hiddenKey]).filterNot { it.substringBefore('/') == packageName })
        }
    }

    suspend fun unhide(id: String) {
        context.dataStore.edit { prefs ->
            prefs[hiddenKey] = encode(decode(prefs[hiddenKey]) - id)
        }
    }

    private fun decode(raw: String?): List<String> =
        raw?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    private fun encode(ids: List<String>): String = ids.joinToString(SEPARATOR)

    private companion object {
        const val SEPARATOR = "|"
    }
}
