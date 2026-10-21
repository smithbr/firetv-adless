package la.bsmith.adless

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * The blurred, full-screen version of an app's banner. The TV is API 30 and
 * slow, so there is no RenderEffect: the banner is shrunk to a few thousand
 * pixels, box-blurred three times (close to a Gaussian), and left small; the
 * screen-sized Image scales it up with bilinear filtering, which is the rest
 * of the blur. Each result is tiny and is made once per app.
 */
object Backdrops {
    private const val WIDTH = 96
    private const val HEIGHT = 54
    private const val RADIUS = 2
    private const val PASSES = 3

    /** The banner's average colour, lifted so it reads as a tint over a dark backdrop. */
    fun tint(source: ImageBitmap): Int {
        val small = Bitmap.createScaledBitmap(source.asAndroidBitmap(), 16, 9, true)
        val pixels = IntArray(16 * 9)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 9)
        var r = 0L; var g = 0L; var b = 0L
        for (p in pixels) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
        val n = pixels.size
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.4f).coerceIn(0.25f, 1f)
        hsv[2] = hsv[2].coerceIn(0.55f, 0.9f)
        return android.graphics.Color.HSVToColor(hsv)
    }

    /**
     * A 1-bit version of an icon or banner: shrunk to [size] square, each
     * pixel dark or light by its luminance against the mean. Drawn with no
     * filtering it stays blocky. Made once per app.
     */
    fun oneBit(source: ImageBitmap, size: Int, dark: Int, light: Int): ImageBitmap {
        val small = Bitmap.createScaledBitmap(source.asAndroidBitmap(), size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val a = p ushr 24
            // Transparent pixels count as light background.
            if (a < 64) 255 else (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        val mean = luma.average()
        val out = IntArray(pixels.size) { i -> if (luma[i] < mean) dark else light }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(out, 0, size, 0, 0, size, size)
        return bitmap.asImageBitmap()
    }

    fun blur(source: ImageBitmap): ImageBitmap {
        val small = Bitmap.createScaledBitmap(source.asAndroidBitmap(), WIDTH, HEIGHT, true)
        val pixels = IntArray(WIDTH * HEIGHT)
        small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        val scratch = IntArray(WIDTH * HEIGHT)
        repeat(PASSES) {
            boxBlur(pixels, scratch, WIDTH, HEIGHT, horizontal = true)
            boxBlur(scratch, pixels, WIDTH, HEIGHT, horizontal = false)
        }
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        return out.asImageBitmap()
    }

    /** One box-blur pass along rows or columns, edges clamped, into [dst]. */
    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val window = RADIUS * 2 + 1
        for (line in 0 until lines) {
            fun index(i: Int) = if (horizontal) line * w + i else i * w + line
            for (i in 0 until length) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -RADIUS..RADIUS) {
                    val p = src[index((i + k).coerceIn(0, length - 1))]
                    a += p ushr 24
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                dst[index(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            }
        }
    }
}
