package com.system.update
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Executors
class LiveService : Service() {
    private val owner = CameraOwner()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var server: MjpegServer
    private var provider: ProcessCameraProvider? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        notify_()
        server = MjpegServer(8080).also { it.start() }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var last = 0L
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    if (now - last > 120) {
                        last = now
                        runCatching {
                            val bmp = proxy.toBitmap()
                            val rot = Bitmap.createBitmap(bmp, 0, 0,
                                bmp.width, bmp.height,
                                android.graphics.Matrix().apply {
                                    postRotate(proxy.imageInfo.rotationDegrees.toFloat())
                                }, true)
                            val out = ByteArrayOutputStream()
                            rot.compress(Bitmap.CompressFormat.JPEG, 55, out)
                            server.push(out.toByteArray())
                        }
                    }
                    proxy.close()
                }
                owner.start()
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) { stopSelf() }
        }, ContextCompat.getMainExecutor(this))
        return START_STICKY
    }
    private fun notify_() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("live", "Live", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 0,
            Intent(this, LiveService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "live")
            .setContentTitle("System Update")
            .setContentText("http://localhost:8080")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .addAction(0, "Stop", stop)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(2, n)
    }
    override fun onDestroy() {
        runCatching { provider?.unbindAll() }
        runCatching { server.stop() }
        owner.stop()
        executor.shutdown()
        super.onDestroy()
    }
}
class MjpegServer(port: Int) : NanoHTTPD(port) {
    @Volatile private var latest: ByteArray? = null
    @Volatile private var alive = true
    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/") return newFixedLengthResponse("live")
        val pipe = PipedOutputStream()
        val input = PipedInputStream(pipe, 1 shl 20)
        Thread {
            try {
                while (alive) {
                    val f = latest
                    if (f != null) {
                        pipe.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray())
                        pipe.write(f)
                        pipe.write("\r\n".toByteArray())
                        pipe.flush()
                    }
                    Thread.sleep(100)
                }
            } catch (_: Exception) {}
        }.start()
        return newChunkedResponse(Response.Status.OK,
            "multipart/x-mixed-replace; boundary=frame", input)
            .also { it.addHeader("Cache-Control", "no-cache") }
    }
    fun push(jpeg: ByteArray) { latest = jpeg }
}