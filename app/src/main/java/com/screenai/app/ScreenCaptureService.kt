package com.screenai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.screenai.app.START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screenai_capture"
        private const val NOTIFICATION_ID = 41
    }

    private var projection: MediaProjection? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlay: View? = null
    private var answerView: TextView? = null
    private var latestBitmap: Bitmap? = null
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(projectionCallback, mainHandler)
            startCapture()
            showOverlay()
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ reader ->
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val buffer: ByteBuffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val padded = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    padded.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                    if (cropped !== padded) padded.recycle()
                    synchronized(this) {
                        latestBitmap?.recycle()
                        latestBitmap = cropped
                    }
                }
            } catch (_: Exception) {
            } finally {
                image?.close()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = projection?.createVirtualDisplay(
            "ScreenAI-Display",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xF0222222.toInt())
        }

        val header = TextView(this).apply {
            text = "ScreenAI  •  перетащи"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(4, 4, 4, 10)
        }
        header.setOnTouchListener(DragTouchListener())

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val solve = Button(this).apply {
            text = "Решить"
            setOnClickListener { analyzeScreen() }
        }
        val close = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { stopSelf() }
        }
        buttons.addView(solve, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(close, LinearLayout.LayoutParams(0, -2, 1f))

        answerView = TextView(this).apply {
            text = "Нажми «Решить», чтобы получить ответ по текущему экрану."
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 10, 8, 10)
        }
        val scroll = ScrollView(this).apply {
            addView(answerView)
        }
        panel.addView(header)
        panel.addView(buttons)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 260))

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        overlay = panel
        windowManager.addView(panel, params)
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = overlay?.layoutParams as? WindowManager.LayoutParams ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlay, params)
                    return true
                }
            }
            return true
        }
    }

    private fun analyzeScreen() {
        if (busy) return
        val prefs = getSharedPreferences("screenai", MODE_PRIVATE)
        val provider = prefs.getString("provider", "gemini") ?: "gemini"
        val key = prefs.getString("key_$provider", "").orEmpty()
        if (key.isBlank()) {
            answerView?.text = "Сначала выбери ИИ и сохрани его API key в основном приложении."
            return
        }
        val bitmap = synchronized(this) { latestBitmap?.copy(Bitmap.Config.ARGB_8888, false) }
        if (bitmap == null) {
            answerView?.text = "Экран ещё захватывается. Подожди секунду и нажми снова."
            return
        }

        busy = true
        answerView?.text = "Анализирую экран…"
        worker.execute {
            val result = try {
                val bytes = ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    out.toByteArray()
                }
                bitmap.recycle()
                askProvider(provider, key, Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (e: Exception) {
                "Ошибка: ${e.message ?: "не удалось обработать запрос"}"
            }
            mainHandler.post {
                busy = false
                answerView?.text = result
            }
        }
    }


    private fun askProvider(provider: String, apiKey: String, imageBase64: String): String {
        val prompt = "Реши задачу или объясни содержимое на скриншоте. Отвечай по-русски, " +
            "коротко, но показывай основные шаги. Если задания нет или текст неразборчив, скажи об этом."
        val dataUrl = "data:image/jpeg;base64,$imageBase64"

        val endpoint: String
        val body: JSONObject

        when (provider) {
            "gemini" -> {
                endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                body = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("text", prompt))
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject().put("mime_type", "image/jpeg").put("data", imageBase64)
                                    )
                                )
                        )
                    )
                ).put("generationConfig", JSONObject().put("maxOutputTokens", 900))
            }
            "openai" -> {
                endpoint = "https://api.openai.com/v1/chat/completions"
                body = openAiCompatibleBody("gpt-4.1-mini", prompt, dataUrl)
            }
            "deepseek" -> {
                endpoint = "https://api.deepseek.com/chat/completions"
                body = openAiCompatibleBody("deepseek-flash", prompt, dataUrl)
            }
            "groq" -> {
                endpoint = "https://api.groq.com/openai/v1/chat/completions"
                body = openAiCompatibleBody("qwen/qwen3.8-27b", prompt, dataUrl)
            }
            else -> return "Неизвестный ИИ-провайдер."
        }

        val finalUrl = if (provider == "gemini") "$endpoint?key=${Uri.encode(apiKey)}" else endpoint
        val connection = (URL(finalUrl).openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 20000
        connection.readTimeout = 60000
        connection.doOutput = true
        if (provider != "gemini") connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream.bufferedReader().use { it.readText() }
        connection.disconnect()

        if (status !in 200..299) {
            val message = try {
                val error = JSONObject(responseText).optJSONObject("error")
                error?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: error?.optString("status")
            } catch (_: Exception) { null }
            return "Ошибка API ($status): ${message ?: responseText.take(350)}"
        }

        val json = JSONObject(responseText)
        return when (provider) {
            "gemini" -> {
                val candidates = json.optJSONArray("candidates")
                val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val out = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) {
                    out.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                }
                out.toString().ifBlank { "Gemini не вернул текстовый ответ." }
            }
            else -> json.optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: "ИИ не вернул текстовый ответ."
        }
    }

    private fun openAiCompatibleBody(model: String, prompt: String, dataUrl: String): JSONObject {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put(
                    "type", "image_url"
                ).put(
                    "image_url", JSONObject().put("url", dataUrl).put("detail", "high")
                )
            )
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", content)
        )
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 900)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ScreenAI захват экрана", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ScreenAI работает")
                .setContentText("Захват экрана активен. Нажми «Решить» в плавающем окне.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ScreenAI работает")
                .setContentText("Захват экрана активен.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        try {
            overlay?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        synchronized(this) {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        worker.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
