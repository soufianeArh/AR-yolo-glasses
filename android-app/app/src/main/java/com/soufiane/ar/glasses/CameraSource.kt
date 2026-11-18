package com.soufiane.ar.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size

/**
 * Step 8.7: the device camera as the frame source. On the emulator the back camera is the PC
 * webcam (AVD: hw.camera.back = webcam0); on the glasses it will be their camera (Step 9 may need
 * a Goolton-specific source if WEBB OS does not expose it through Camera2).
 *
 * Plain Camera2 (no extra library): a repeating request into an ImageReader that delivers small
 * JPEGs; frames beyond [fps] are dropped right away (newest wins, no queue). Position = ms since start.
 * Needs the CAMERA permission (MainActivity asks for it).
 */
class CameraSource(private val context: Context, private val maxWidth: Int = 640) : FrameSource {

    private var thread: HandlerThread? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var size: Size? = null
    private var cameraId = "?"
    @Volatile private var running = false

    override val description: String
        get() = "camera $cameraId ${size?.let { "${it.width}x${it.height}" } ?: ""}"

    @SuppressLint("MissingPermission")                                // checked by MainActivity
    override fun start(fps: Double, onFrame: (android.graphics.Bitmap, Long) -> Unit) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        if (ids.isEmpty()) error("no camera on this device")
        cameraId = ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids[0]
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.JPEG).sortedBy { it.width * it.height }
        val chosen = sizes.lastOrNull { it.width <= maxWidth } ?: sizes.first()
        size = chosen

        val t = HandlerThread("camera").also { it.start() }
        thread = t
        val handler = Handler(t.looper)
        val periodMs = (1000.0 / fps).toLong()
        val t0 = SystemClock.elapsedRealtime()
        var lastAt = 0L
        running = true

        val r = ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.JPEG, 2)
        reader = r
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (!running || now - lastAt < periodMs) {                      // over the fps: drop
                img.close(); return@setOnImageAvailableListener
            }
            lastAt = now
            val buf = img.planes[0].buffer
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            img.close()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { onFrame(it, now - t0) }
        }, handler)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                if (!running) { d.close(); return }
                device = d
                @Suppress("DEPRECATION")
                d.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (!running) { s.close(); return }
                        session = s
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }
                        s.setRepeatingRequest(req.build(), null, handler)
                        Log.i(MainActivity.TAG, "CAMERA_STARTED id=$cameraId size=${chosen.width}x${chosen.height} fps<=$fps")
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(MainActivity.TAG, "CAMERA_ERROR capture session failed")
                    }
                }, handler)
            }
            override fun onDisconnected(d: CameraDevice) {
                Log.w(MainActivity.TAG, "CAMERA_ERROR disconnected"); d.close()
            }
            override fun onError(d: CameraDevice, error: Int) {
                Log.e(MainActivity.TAG, "CAMERA_ERROR code=$error"); d.close()
            }
        }, handler)
    }

    override fun stop() {
        running = false
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        session = null; device = null; reader = null; thread = null
    }
}
