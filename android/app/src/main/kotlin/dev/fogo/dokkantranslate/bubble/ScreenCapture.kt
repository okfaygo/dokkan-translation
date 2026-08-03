package dev.fogo.dokkantranslate.bubble

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Mirrors the screen into an ImageReader for the life of a bubble session.
 *
 * The single VirtualDisplay is deliberate. Android 14+ throws if
 * createVirtualDisplay() is called more than once on a MediaProjection, and
 * a consent Intent may only be exchanged for a token once — so a
 * capture-per-tap design would put a system consent dialog in front of the
 * user on EVERY tap. Instead the mirror stays alive and each tap just pulls
 * the newest frame out of the reader.
 */
class ScreenCapture private constructor(
    private val projection: MediaProjection,
    private val imageReader: ImageReader,
    private val virtualDisplay: VirtualDisplay,
    private val callback: MediaProjection.Callback,
    private val width: Int,
    private val height: Int,
) {

    @Volatile
    private var released = false

    /**
     * Throw away every buffered frame.
     *
     * The reader holds only [MAX_IMAGES] buffers, and while they are all
     * full the mirror cannot post new ones — so without draining first,
     * captureLatest() can hand back a frame from minutes ago instead of
     * what is on screen now. Drain, wait briefly, then capture.
     */
    fun drain() {
        if (released) return
        while (true) {
            val image = runCatching { imageReader.acquireNextImage() }.getOrNull()
                ?: return
            image.close()
        }
    }

    /** Newest mirrored frame, or null if none has arrived yet. */
    fun captureLatest(): Bitmap? {
        if (released) return null
        val image = imageReader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            // the buffer is padded to the row stride; decode wide, then crop
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            return if (padded.width == width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, width, height)
                    .also { if (it !== padded) padded.recycle() }
            }
        } catch (e: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { virtualDisplay.release() }
        runCatching { imageReader.close() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }

    companion object {
        private const val MAX_IMAGES = 2

        /**
         * @param onStopped fired when the system ends the projection — the
         *   user revoking it from the status bar, or (Android 15 QPR1+) the
         *   screen locking. The session cannot be resumed; the caller must
         *   ask for consent again.
         */
        fun create(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            onStopped: () -> Unit,
        ): ScreenCapture? {
            if (resultCode != Activity.RESULT_OK) return null
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val projection = manager.getMediaProjection(resultCode, resultData)
                ?: return null

            val (width, height, density) = screenMetrics(context)
            val reader = ImageReader.newInstance(
                width, height, PixelFormat.RGBA_8888, MAX_IMAGES,
            )

            // Required since Android 14: createVirtualDisplay() throws
            // IllegalStateException without a registered callback.
            val handler = Handler(Looper.getMainLooper())
            val callback = object : MediaProjection.Callback() {
                override fun onStop() = onStopped()
            }
            projection.registerCallback(callback, handler)

            val display = try {
                projection.createVirtualDisplay(
                    "DokkanTranslate",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    handler,
                )
            } catch (e: Exception) {
                projection.unregisterCallback(callback)
                projection.stop()
                reader.close()
                return null
            } ?: run {
                projection.unregisterCallback(callback)
                projection.stop()
                reader.close()
                return null
            }

            return ScreenCapture(projection, reader, display, callback, width, height)
        }

        private fun screenMetrics(context: Context): Triple<Int, Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = context.resources.displayMetrics.densityDpi
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Triple(bounds.width(), bounds.height(), density)
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                Triple(metrics.widthPixels, metrics.heightPixels, density)
            }
        }
    }
}
