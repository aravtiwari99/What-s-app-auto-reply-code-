package com.souleven.wap.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.souleven.wap.R

class SettingsActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var extraInput: EditText
    private lateinit var swRead: Switch
    private lateinit var swReply: Switch
    private lateinit var swGroups: Switch
    private lateinit var swScreen: Switch
    private var binding = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Auto reply"

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(scroll)

        col.addView(ImageView(this).apply {
            setImageResource(R.drawable.waplus_logo)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        })
        col.addView(TextView(this).apply {
            text = "AI Auto-Reply"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(statusView)

        col.addView(button("Notification access (ON karo)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        col.addView(button("Accessibility (ON karo - screen mode ke liye)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("Battery optimization (unrestricted karo)") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        })

        col.addView(section("Switches"))
        swRead = Switch(this).apply { text = "Auto read (messages read + history save)" }
        swReply = Switch(this).apply { text = "Auto reply (AI se jawab)" }
        swGroups = Switch(this).apply { text = "Group chats me bhi reply (not recommended)" }
        swScreen = Switch(this).apply { text = "Screen mode: WhatsApp open ho tab bhi chat kholke reply" }
        for (s in listOf(swRead, swReply, swScreen, swGroups)) {
            s.setPadding(0, dp(10), 0, dp(10))
            col.addView(s)
        }
        swRead.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            AiPrefs.setAutoRead(this, on)
            if (!on) {
                AiPrefs.setAutoReply(this, false)
                setChecked(swReply, false)
            }
        }
        swReply.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            AiPrefs.setAutoReply(this, on)
            if (on) {
                AiPrefs.setAutoRead(this, true)   // reply needs read
                setChecked(swRead, true)
                if (AiPrefs.apiKey(this).isEmpty()) toast("Pehle API key set karo")
            }
        }
        swScreen.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            AiPrefs.setScreenMode(this, on)
            if (on) {
                if (!isAccessibilityEnabled()) toast("Accessibility service ON karo")
                if (!AiPrefs.autoReply(this)) toast("Auto reply bhi ON karo")
            }
        }
        swGroups.setOnCheckedChangeListener { _, on ->
            if (binding) return@setOnCheckedChangeListener
            AiPrefs.setReplyGroups(this, on)
        }

        col.addView(section("Groq API key"))
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "gsk_..."
            isSingleLine = true
        }
        col.addView(keyInput)
        col.addView(row(
            button("Save / Change key") {
                val k = keyInput.text.toString().trim()
                if (k.isEmpty()) {
                    toast("Key khali hai")
                } else {
                    AiPrefs.setApiKey(this, k)
                    keyInput.setText("")
                    toast("API key saved")
                    refresh()
                }
            },
            button("Remove key") {
                AiPrefs.setApiKey(this, "")
                AiPrefs.setAutoReply(this, false)
                toast("API key removed")
                refresh()
            }
        ))

        col.addView(section("Model"))
        modelInput = EditText(this).apply { isSingleLine = true }
        col.addView(modelInput)
        col.addView(row(
            button("Save model") {
                AiPrefs.setModel(this, modelInput.text.toString())
                toast("Model saved")
                refresh()
            },
            button("Test API") { testApi() }
        ))

        col.addView(section("Extra instructions (optional)"))
        extraInput = EditText(this).apply {
            hint = "Jaise: main busy hoon, jaruri ho to call karo"
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(AiPrefs.extraInstructions(this@SettingsActivity))
        }
        col.addView(extraInput)
        extraInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                AiPrefs.setExtraInstructions(this@SettingsActivity, s?.toString().orEmpty())
            }
        })
        col.addView(button("Save instructions") {
            AiPrefs.setExtraInstructions(this, extraInput.text.toString())
            toast("Instructions saved and will be used for replies")
        })

        col.addView(section("Data"))
        col.addView(button("Chat history delete karo") {
            ChatStore.clearAll(this)
            toast("History deleted")
        })

        col.addView(TextView(this).apply {
            text = "Note: reply bhejne ke liye WhatsApp ka notification me 'Reply' option hona chahiye. " +
                "For screen-off replies, keep Notification access on and set this app's battery usage to Unrestricted; Android may still delay network access in Doze. " +
                "Image analysis works only when the WhatsApp chat and image are visible with Screen mode on, Android 11 or newer, and a vision-capable Groq model selected. " +
                "Only the visible image crop is sent to Groq; images cannot be read from the lock screen. Chat messages are also sent to Groq. " +
                "Messages are processed after 2 seconds of quiet; each incoming message gets its own reply, with at least 2 seconds between replies to the same chat. " +
                "Unknown phone numbers are asked to identify themselves before questions are answered."
            textSize = 12f
            setPadding(0, dp(20), 0, 0)
        })

        col.addView(section("Setup guide (English)"))
        col.addView(TextView(this).apply {
            text = "Create a Groq API key\n" +
                "1. Open the Groq Console using the link below.\n" +
                "2. Sign in to your Groq account, or create an account and then sign in.\n" +
                "3. Open API Keys from the console menu (or visit the API keys page below).\n" +
                "4. Select Create API Key, enter a name, and confirm.\n" +
                "5. Copy the key and paste it in the Groq API key field above, then tap Save / Change key. Keep the key private."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(8))
        })
        col.addView(TextView(this).apply {
            text = "https://console.groq.com/keys"
            textSize = 14f
            setTextColor(Color.rgb(21, 101, 192))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(4), 0, dp(12))
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.groq.com/keys")))
            }
        })
        col.addView(TextView(this).apply {
            text = "Allow restricted settings (if Android blocks access)\n" +
                "1. Open Settings → Apps → Auto reply.\n" +
                "2. Tap the three-dot menu (⋮) in the top-right corner.\n" +
                "3. Tap Allow restricted settings and confirm. This option may not appear on every Android version or phone.\n\n" +
                "Turn on Accessibility\n" +
                "1. Open Settings → Accessibility → General → Downloaded apps → Auto reply.\n" +
                "2. Turn on the Accessibility service and confirm the prompt.\n\n" +
                "Turn on Notification access\n" +
                "1. Open Settings → Apps → Special app access → Device & app notifications or Notification access.\n" +
                "2. Select Auto reply and allow notification access.\n" +
                "3. Settings names and paths vary by phone. You can also use the Notification access and Accessibility buttons at the top of this screen."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(20))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding = true
        swRead.isChecked = AiPrefs.autoRead(this)
        swReply.isChecked = AiPrefs.autoReply(this)
        swGroups.isChecked = AiPrefs.replyGroups(this)
        swScreen.isChecked = AiPrefs.screenMode(this)
        binding = false

        modelInput.setText(AiPrefs.model(this))
        val masked = AiPrefs.maskedKey(this)
        keyInput.hint = if (masked.isEmpty()) "gsk_... (key set nahi hai)" else "Current: $masked  (naya paste karo)"

        val access = isListenerEnabled()
        val last = AiPrefs.status(this)
        statusView.text = buildString {
            append("Notification access: ").append(if (access) "ON ✅" else "OFF ❌ (upar button se ON karo)").append('\n')
            append("Accessibility (screen mode): ").append(if (isAccessibilityEnabled()) "ON ✅" else "OFF ❌").append('\n')
            append("API key: ").append(if (masked.isEmpty()) "not set ❌" else "set ✅ ($masked)").append('\n')
            append("Model: ").append(AiPrefs.model(this@SettingsActivity))
            if (last.isNotEmpty()) append('\n').append("Last: ").append(last)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: return false
        return flat.contains("$packageName/")
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun testApi() {
        val key = AiPrefs.apiKey(this)
        if (key.isEmpty()) {
            toast("Pehle API key save karo")
            return
        }
        val model = modelInput.text.toString().trim().ifEmpty { AiPrefs.model(this) }
        toast("Testing...")
        Thread {
            val msg = try {
                val r = GroqClient.chat(key, model, listOf("user" to "Reply with one word: hi"))
                "OK: " + r.take(60)
            } catch (t: Throwable) {
                "Failed: " + t.message
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun setChecked(s: Switch, v: Boolean) {
        binding = true
        s.isChecked = v
        binding = false
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        addView(a, lp())
        addView(b, lp())
    }
}
