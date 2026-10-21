package la.bsmith.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** An app the launcher can open: one entry per package, TV launcher activity preferred. */
data class AppEntry(
    val id: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val image: ImageBitmap?,
    /** True when [image] is a 16:9 TV banner; false for a square icon. */
    val isBanner: Boolean,
    /** A system package cannot be uninstalled; it can only be hidden. */
    val isSystem: Boolean,
)

/** A fixed entry that is not an app in the launcher list, such as a settings screen. */
data class SystemEntry(val label: String, val intent: Intent)

/** A short, distinct code for a settings entry, for tiles that fit letters only. */
fun shortCode(label: String): String = when (label) {
    "Settings" -> "SET"
    "Network" -> "NET"
    "Display & Sounds" -> "DSP"
    "Applications" -> "APP"
    "Controllers" -> "CTL"
    "Preferences" -> "PRF"
    "My Account" -> "ACC"
    "Device & Software" -> "DEV"
    "Appstore" -> "STR"
    "Hidden apps" -> "HID"
    "Theme" -> "THM"
    else -> label.split(' ', '&', ':').filter { it.isNotBlank() && it.first().isLetter() }.take(3).joinToString("") { it.first().uppercase() }
}

class AppRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun loadApps(): List<AppEntry> {
        val leanback = query(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phone = query(Intent.CATEGORY_LAUNCHER)
        // TV apps, plus sideloaded phone apps; system packages that only
        // have a phone launcher entry (quick settings, the screensaver,
        // Amazon's own home) are not apps a person opens from here.
        val byPackage = LinkedHashMap<String, ResolveInfo>()
        for (info in leanback) {
            byPackage.putIfAbsent(info.activityInfo.packageName, info)
        }
        for (info in phone) {
            val system = info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
            if (!system) byPackage.putIfAbsent(info.activityInfo.packageName, info)
        }
        byPackage.remove(context.packageName)
        return byPackage.values.map(::toEntry).sortedBy { it.label.lowercase() }
    }

    private fun query(category: String): List<ResolveInfo> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)

    private fun toEntry(info: ResolveInfo): AppEntry {
        val activity = info.activityInfo
        val banner: Drawable? = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
        val drawable = banner ?: info.loadIcon(pm)
        val image = if (banner != null) drawable.toBitmap(320, 180) else drawable.toBitmap(160, 160)
        val component = ComponentName(activity.packageName, activity.name)
        return AppEntry(
            id = component.flattenToString(),
            label = info.loadLabel(pm).toString(),
            packageName = activity.packageName,
            component = component,
            image = image.asImageBitmap(),
            isBanner = banner != null,
            isSystem = activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }

    /**
     * Fire TV keeps its settings menu inside the stock launcher package. Once
     * that is disabled, the sections are still reachable in the settings
     * package, so this lists whichever of the two exist on the device.
     */
    fun settingsEntries(): List<SystemEntry> {
        val entries = mutableListOf<SystemEntry>()
        val main = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS")
        if (pm.resolveActivity(main, 0) != null) {
            entries += SystemEntry("Settings", main)
        }
        val settings = "com.amazon.tv.settings.v2"
        val sections = listOf(
            "Network" to ".tv.network.NetworkActivity",
            "Display & Sounds" to ".tv.display_sounds.DisplayAndSoundsActivity",
            "Applications" to ".tv.applications.ApplicationsActivity",
            "Controllers" to ".tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity",
            "Preferences" to ".tv.preferences.PreferencesActivity",
            "My Account" to ".tv.my_account.MyAccountActivity",
            "Device & Software" to ".tv.device.DeviceActivity",
        )
        for ((label, activity) in sections) {
            // By component and with no action: given ACTION_MAIN, the
            // Applications page expects a package extra and crashes on null.
            val intent = Intent().setComponent(ComponentName(settings, settings + activity))
            if (startable(intent)) entries += SystemEntry(label, intent)
        }
        // The Appstore's home, resolved at runtime: its featured-page action
        // first (its launcher activity is the apps grid, which hands over to
        // Amazon's home screen), then whatever launch intent the package
        // offers; no entry at all when the store is disabled or missing.
        val storePackage = "com.amazon.venezia"
        val store = listOf(
            Intent("com.amazon.venezia.ade.FEATURED_LAUNCH").setPackage(storePackage),
            pm.getLeanbackLaunchIntentForPackage(storePackage),
            pm.getLaunchIntentForPackage(storePackage),
        ).firstOrNull { it != null && startable(it) }
        if (store != null) entries += SystemEntry("Appstore", store)
        return entries
    }

    /**
     * Whether the intent resolves to an activity this app may start. Some
     * Fire TV settings sections (Display & Sounds, Preferences, My Account on
     * Fire OS 8.1.8) demand a permission only Amazon's launcher holds; they
     * stay reachable through the main Settings entry and are left out here.
     */
    private fun startable(intent: Intent): Boolean {
        val activity = pm.resolveActivity(intent, 0)?.activityInfo ?: return false
        val permission = activity.permission ?: return true
        return pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
    }

    fun launch(entry: AppEntry): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(entry.packageName)
            ?: pm.getLaunchIntentForPackage(entry.packageName)
            ?: Intent(Intent.ACTION_MAIN).setComponent(entry.component)
        return start(intent)
    }

    /**
     * The settings sections share one task. Brought to front as a launcher
     * would, that task shows whichever section opened it first, so each
     * launch clears it and starts the chosen section fresh.
     */
    fun launch(entry: SystemEntry): Boolean =
        start(Intent(entry.intent), Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /** Asks the system to uninstall the app; its own dialog confirms. Never silent. */
    fun uninstall(entry: AppEntry): Boolean {
        if (entry.isSystem) return false
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
            .putExtra(Intent.EXTRA_TITLE, entry.label)
        return start(intent)
    }

    private fun start(
        intent: Intent,
        flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
    ): Boolean {
        intent.addFlags(flags)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
