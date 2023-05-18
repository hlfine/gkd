package li.songe.gkd.debug.server.api

import android.os.Build
import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val device: String = Build.DEVICE,
    val model: String = Build.MODEL,
    val manufacturer: String = Build.MANUFACTURER,
    val brand: String = Build.BRAND,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE,
) {
    companion object {
        val singleton by lazy { Device() }
    }
}
