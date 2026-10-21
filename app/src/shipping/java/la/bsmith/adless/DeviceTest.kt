package la.bsmith.adless

import android.app.Activity

/** The release and debug builds read no test extras; see src/devicetest. */
object DeviceTest {
    val clockMillis: Long? = null
    val freezeBackdrop: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun apply(activity: Activity) = Unit
}
