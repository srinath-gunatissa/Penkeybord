package com.penkeyboard.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

object Prefs {
    const val NAME = "pen_keyboard"
    const val PEN_ONLY = "pen_only"
    const val DELAY_MS = "delay_ms"
    const val PAD_HEIGHT = "pad_height"
    const val SINGLISH = "singlish"
    const val WORDS = "learned_words"
    const val CLIPS = "clips"
}

/** Setup screen: turn the keyboard on, choose it, tweak settings, try it out. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var enabledText: TextView
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pad = (20 * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        col.addView(text("Pen Keyboard", 26f, true))
        col.addView(text("Write with your S Pen in any app. Your writing turns into text when you pause."))

        enabledText = text("", 15f)
        col.addView(enabledText)
        col.addView(Button(this).apply {
            text = "1. Turn on Pen Keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "2. Switch to Pen Keyboard"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })

        status = text("Handwriting model: checking…", 15f)
        col.addView(status)

        col.addView(text("Settings", 20f, true))
        col.addView(Switch(this).apply {
            text = "Pen only (ignore my hand and fingers)"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.PEN_ONLY, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.PEN_ONLY, on).apply() }
        })

        val delayLabel = text("")
        fun showDelay(ms: Int) { delayLabel.text = "Turn writing into text after a pause of ${"%.1f".format(ms / 1000f)} s" }
        val delay = prefs.getInt(Prefs.DELAY_MS, 700)
        showDelay(delay)
        col.addView(delayLabel)
        col.addView(SeekBar(this).apply {
            max = 1700; progress = delay - 300
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    showDelay(v + 300); prefs.edit().putInt(Prefs.DELAY_MS, v + 300).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val heightLabel = text("")
        fun showHeight(h: Int) { heightLabel.text = "Writing area height: $h (reopen the keyboard to see it)" }
        val h = prefs.getInt(Prefs.PAD_HEIGHT, 230)
        showHeight(h)
        col.addView(heightLabel)
        col.addView(SeekBar(this).apply {
            max = 240; progress = h - 140
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    showHeight(v + 140); prefs.edit().putInt(Prefs.PAD_HEIGHT, v + 140).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        col.addView(text("Try it here", 20f, true))
        col.addView(EditText(this).apply {
            hint = "Tap here, then write below"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP
        })

        col.addView(text(
            "Tips:\n• Write a word or a short phrase, then pause.\n" +
            "• Write in English or Sinhala — no switching needed.\n" +
            "• Tap a suggestion at the top to fix a word.\n" +
            "• ⌫ removes your last stroke while you're still writing.\n" +
            "• Hold the S Pen button and rub to erase strokes.\n" +
            "• ⌨ switches back to your normal keyboard.\n\n" +
            "Keyboard buttons:\n" +
            "• Two writing boxes: write in one while the other turns into text.\n" +
            "• 123 → numbers & symbols, Edit (copy, paste, undo, arrows), emoji, clipboard.\n" +
            "• සිං → Singlish: \"mama gedara yanawa\" becomes මම ගෙදර යනව.\n" +
            "  (Use capital T D N L for ට ඩ ණ ළ.) When off, the Sinhala version is still in suggestions.\n" +
            "• It learns the words you use and suggests them first.\n\n" +
            "Pen gestures:\n" +
            "• Scribble over writing → erase it\n" +
            "• Scribble on an empty pad → delete the last word\n" +
            "• Long line left → right → space\n" +
            "• Long line right → left → backspace\n" +
            "• Line down, then left (↵) → Enter", 15f))

        col.addView(Button(this).apply {
            text = "Forget learned words"
            setOnClickListener { WordMemory(this@MainActivity).forgetAll(); text = "✓ Learned words cleared" }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val on = imm.enabledInputMethodList.any { it.packageName == packageName }
        enabledText.text = if (on) "✓ Pen Keyboard is turned on" else "Pen Keyboard is not turned on yet"
        InkEngine.ensure {
            status.text = "Handwriting languages (detected automatically):\n" +
                InkEngine.LANGS.joinToString("\n") { lang ->
                    "• ${lang.name}: " + when (InkEngine.states[lang.tag]) {
                        InkEngine.State.READY -> "✓ ready (works offline)"
                        InkEngine.State.DOWNLOADING -> "downloading (one time, needs internet)…"
                        InkEngine.State.FAILED -> "not available. Check internet and reopen this app."
                        else -> "checking…"
                    }
                }
        }
    }
}
