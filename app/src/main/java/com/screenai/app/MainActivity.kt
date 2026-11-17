
package com.screenai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

data class AiProvider(
    val id: String,
    val title: String,
    val keyUrl: String,
    val hint: String
)

class MainActivity : ComponentActivity() {
    private val providers = listOf(
        AiProvider("gemini", "Google Gemini", "https://aistudio.google.com/app/apikey", "Gemini API key"),
        AiProvider("openai", "OpenAI", "https://platform.openai.com/api-keys", "OpenAI API key"),
        AiProvider("deepseek", "DeepSeek", "https://platform.deepseek.com/api_keys", "DeepSeek API key"),
        AiProvider("groq", "Groq", "https://console.groq.com/keys", "Groq API key")
    )

    private lateinit var keyInput: EditText
    private lateinit var selectedLabel: TextView
    private val providerSelectButtons = mutableMapOf<String, Button>()
    private var selectedProviderId = "gemini"
    private var waitingForOverlay = false

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                Toast.makeText(this, "ScreenAI запущен", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Захват экрана не разрешён", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("screenai", Context.MODE_PRIVATE)
        selectedProviderId = prefs.getString("provider", "gemini") ?: "gemini"

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 30, 22, 24)
        }
        val scroll = ScrollView(this).apply { addView(page) }

        val title = TextView(this).apply {
            text = "ScreenAI"
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "ИИ-помощник поверх экрана"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 20)
        }
        selectedLabel = TextView(this).apply {
            textSize = 16f
            setPadding(0, 12, 0, 6)
        }
        keyInput = EditText(this).apply {
            hint = "API key"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val saveKey = Button(this).apply {
            text = "Сохранить ключ выбранного ИИ"
            setOnClickListener {
                val key = keyInput.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Вставь API key", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("key_$selectedProviderId", key).apply()
                    Toast.makeText(this@MainActivity, "Ключ сохранён", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val start = Button(this).apply {
            text = "Запустить"
            setOnClickListener { beginStartFlow() }
        }
        val stop = Button(this).apply {
            text = "Остановить"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenCaptureService::class.java))
                Toast.makeText(this@MainActivity, "ScreenAI остановлен", Toast.LENGTH_SHORT).show()
            }
        }

        val providersTitle = TextView(this).apply {
            text = "ПРОВАЙДЕРЫ ИИ"
            textSize = 17f
            setPadding(0, 24, 0, 8)
        }
        page.addView(title, match())
        page.addView(subtitle, match())
        page.addView(selectedLabel, match())
        page.addView(keyInput, match())
        page.addView(saveKey, match())
        page.addView(start, match())
        page.addView(stop, match())
        page.addView(providersTitle, match())

        providers.forEach { provider ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 8, 12, 8)
                setBackgroundColor(0xFFEFEFEF.toInt())
            }
            val name = TextView(this).apply {
                text = provider.title
                textSize = 16f
                setTextColor(0xFF171717.toInt())
            }
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val select = Button(this).apply {
                text = if (provider.id == selectedProviderId) "Выбран" else "Выбрать"
                setOnClickListener { chooseProvider(provider) }
            }
            providerSelectButtons[provider.id] = select
            val getKey = Button(this).apply {
                text = "Получить API"
                setOnClickListener { openProviderPage(provider.keyUrl) }
            }
            actions.addView(select, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(getKey, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(name, match())
            card.addView(actions, match())
            page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        }

        val note = TextView(this).apply {
            text = "API-ключи и запросы принадлежат выбранному провайдеру; бесплатные лимиты и условия различаются. " +
                "Ключ хранится локально в настройках приложения. Не делись им и не вставляй ключ на чужом устройстве. " +
                "Скриншот отправляется провайдеру только после нажатия «Решить»."
            textSize = 13f
            setPadding(0, 14, 0, 10)
        }
        page.addView(note, match())
        setContentView(scroll)
        refreshProviderUi()
    }

    private fun match() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun chooseProvider(provider: AiProvider) {
        selectedProviderId = provider.id
        getSharedPreferences("screenai", MODE_PRIVATE).edit()
            .putString("provider", provider.id).apply()
        refreshProviderUi()
    }

    private fun refreshProviderUi() {
        val provider = providers.first { it.id == selectedProviderId }
        providerSelectButtons.forEach { (id, button) ->
            button.text = if (id == selectedProviderId) "Выбран" else "Выбрать"
        }
        selectedLabel.text = "Выбранный ИИ: ${provider.title}"
        keyInput.hint = provider.hint
        keyInput.setText(
            getSharedPreferences("screenai", MODE_PRIVATE)
                .getString("key_${provider.id}", "")
        )
        // Keep the selected provider visible even if the screen is rebuilt later.
    }

    private fun openProviderPage(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            intent.setPackage("com.android.chrome")
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(this, "Не найден браузер для открытия страницы", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun beginStartFlow() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Сначала вставь и сохрани API key", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("screenai", MODE_PRIVATE).edit()
            .putString("key_$selectedProviderId", key)
            .putString("provider", selectedProviderId)
            .apply()

        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        requestScreenCapture()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(this)) requestScreenCapture()
            else Toast.makeText(this, "Разрешение поверх окон не выдано", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }
}
