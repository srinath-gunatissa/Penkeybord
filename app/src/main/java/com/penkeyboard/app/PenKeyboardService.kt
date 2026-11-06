package com.penkeyboard.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea

class PenKeyboardService : InputMethodService(), InkView.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    private lateinit var memory: WordMemory

    private lateinit var candBar: LinearLayout
    private lateinit var pads: List<InkView>
    private lateinit var writeArea: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var panelBody: FrameLayout
    private lateinit var modeKey: TextView
    private lateinit var singlishKey: TextView
    private var panelTab = "123"
    private var lastPad: InkView? = null

    private var lastCommit: String? = null
    private var recognizing = false
    private var stickyLang = "en-US"
    private val timers = HashMap<InkView, Runnable>()

    private var clipboard: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { saveClip() }

    override fun onCreate() {
        super.onCreate()
        memory = WordMemory(this)
        clipboard = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.addPrimaryClipChangedListener(clipListener)
        }
    }

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    private fun saveClip() {
        try {
            val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrBlank()) memory.addClip(text)
        } catch (e: Exception) { /* not allowed right now */ }
    }

    // Landscape tablets would otherwise switch to a full-screen editor.
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFECE7DE.toInt())
            setPadding(px(6), px(4), px(6), px(6))
        }

        // Suggestions bar
        candBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candBar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)))

        val padHeight = px(prefs.getInt(Prefs.PAD_HEIGHT, 230))
        val content = FrameLayout(this)

        // Two writing boxes: write in one while the other turns into text.
        writeArea = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pads = listOf(InkView(this, this), InkView(this, this))
        pads.forEachIndexed { i, pad ->
            pad.background = GradientDrawable().apply {
                setColor(0xFFFFFDF8.toInt()); cornerRadius = 14f * dp
                setStroke(px(1), 0xFFDDD5C7.toInt())
            }
            writeArea.addView(pad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i == 0) marginEnd = px(4) else marginStart = px(4)
            })
        }
        content.addView(writeArea, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, padHeight))

        // Button panel: numbers, edit tools, emoji, clipboard
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((id, label) in listOf("123" to "123 #", "edit" to "✂ Edit", "emoji" to "😀", "clip" to "📋 Clipboard")) {
            tabs.addView(key(label, 1f, small = true) { showPanel(id) })
        }
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(38)).apply { bottomMargin = px(4) })
        panelBody = FrameLayout(this)
        panel.addView(panelBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, padHeight))

        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, padHeight)
            .apply { bottomMargin = px(6) })

        // Bottom key row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(key("⌨", 1f) { switchKeyboard() })
        modeKey = key("123", 1.2f) { togglePanel() }
        row.addView(modeKey)
        singlishKey = key("සිං", 1.1f) { toggleSinglish() }
        row.addView(singlishKey)
        for (p in listOf(",", ".", "?")) row.addView(key(p, 0.8f) { typeText(p) })
        row.addView(key("space", 3.5f) { space() })
        row.addView(repeatKey("⌫", 1.3f) { backspace() })
        row.addView(key("↵", 1.3f, accent = true) { enter() })
        row.addView(key("⌄", 1f) { requestHideSelf(0) })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)))

        updateSinglishKey()
        showStatus("Getting handwriting ready…")
        InkEngine.ensure { onEngineState() }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::pads.isInitialized) return
        val penOnly = prefs.getBoolean(Prefs.PEN_ONLY, true)
        pads.forEachIndexed { i, p ->
            p.penOnly = penOnly
            p.hint = if (i == 0) "Write here…" else "…and keep going here"
            p.clear()
        }
        timers.values.forEach { handler.removeCallbacks(it) }
        lastCommit = null
        showWriting()
        InkEngine.ensure { onEngineState() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        timers.values.forEach { handler.removeCallbacks(it) }
        super.onFinishInputView(finishingInput)
    }

    private fun onEngineState() {
        if (lastCommit != null) return
        val downloading = InkEngine.LANGS.filter { InkEngine.states[it.tag] == InkEngine.State.DOWNLOADING }
        when {
            downloading.isNotEmpty() ->
                showStatus("Downloading ${downloading.joinToString(" + ") { it.name }} handwriting (one time, needs Wi-Fi)…")
            InkEngine.state == InkEngine.State.READY -> showStatus("")
            InkEngine.state == InkEngine.State.FAILED ->
                showStatus("Couldn't download the handwriting models. Check internet and reopen.")
            else -> showStatus("Getting handwriting ready…")
        }
    }

    // ---- writing pads ----
    override fun onStrokeStart(pad: InkView) {
        cancel(pad)
        lastPad = pad
        // Started writing in the other box? Turn the first box into text right away.
        pads.firstOrNull { it !== pad && !it.isEmpty }?.let { recognizePad(it, null) }
    }

    override fun onStrokeEnd(pad: InkView) {
        val stroke = pad.strokes.lastOrNull() ?: return
        val others = pad.strokes.dropLast(1)
        when (val g = Gestures.classify(stroke, others, pad.width.toFloat(), dp)) {
            is Gestures.G.EraseStrokes -> {
                pad.removeStrokes(g.strokes + listOf(stroke))
                if (pad.isEmpty) cancel(pad) else schedule(pad)
            }
            Gestures.G.DeleteWord -> { pad.removeStrokes(listOf(stroke)); deleteWord() }
            Gestures.G.Space -> { pad.removeStrokes(listOf(stroke)); space() }
            Gestures.G.Backspace -> { pad.removeStrokes(listOf(stroke)); backspace() }
            Gestures.G.Enter -> { pad.removeStrokes(listOf(stroke)); enter() }
            Gestures.G.None -> schedule(pad)
        }
    }

    override fun onErase(pad: InkView) { if (!pad.isEmpty) schedule(pad) }

    private fun schedule(pad: InkView) {
        cancel(pad)
        val r = timers.getOrPut(pad) { Runnable { recognizePad(pad, null) } }
        handler.postDelayed(r, prefs.getInt(Prefs.DELAY_MS, 700).toLong())
    }

    private fun cancel(pad: InkView) { timers[pad]?.let { handler.removeCallbacks(it) } }

    private val anyInk get() = ::pads.isInitialized && pads.any { !it.isEmpty }

    /** Turn every box with writing into text (oldest first), then run [after]. */
    private fun recognizeAll(after: (() -> Unit)?) {
        val queue = if (::pads.isInitialized) pads.filter { !it.isEmpty }.sortedBy { it.firstTime } else emptyList()
        fun next(i: Int) {
            if (i >= queue.size) after?.invoke() else recognizePad(queue[i]) { next(i + 1) }
        }
        next(0)
    }

    private fun recognizePad(pad: InkView, after: (() -> Unit)?) {
        cancel(pad)
        if (pad.isEmpty) { after?.invoke(); return }
        if (recognizing) { handler.postDelayed({ recognizePad(pad, after) }, 120); return }
        val recs = InkEngine.recognizers.toMap()
        if (recs.isEmpty()) { InkEngine.ensure { onEngineState() }; return }
        val ic = currentInputConnection ?: return
        val pre = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
        val context = RecognitionContext.builder()
            .setPreContext(pre)
            .setWritingArea(WritingArea(pad.width.toFloat(), pad.height.toFloat()))
            .build()
        val count = pad.strokes.size
        val inkObj = pad.toInk(count)
        val preferred = preferredLang(pre)

        // Run every language at once, then pick the best fit.
        val results = mutableMapOf<String, List<Pair<String, Float?>>>()
        var pending = recs.size
        recognizing = true
        fun done() {
            pending--
            if (pending > 0) return
            recognizing = false
            val winner = pickLanguage(results, preferred)
            if (winner == null) {
                showStatus("Couldn't read that. Try writing a bit bigger.")
                return
            }
            stickyLang = winner
            val options = mutableListOf<Pair<String, String>>()
            memory.rank(results[winner]!!.take(5).map { it.first }).forEach { options.add(it to winner) }

            // Singlish: English letters → Sinhala
            val top = options[0].first
            if (Singlish.canConvert(top)) {
                val si = Singlish.convert(top) to "si"
                if (prefs.getBoolean(Prefs.SINGLISH, false)) options.add(0, si) else options.add(1, si)
            }
            results.filterKeys { it != winner }.forEach { (tag, list) ->
                list.take(3).forEach { options.add(it.first to tag) }
            }
            val unique = options.distinctBy { it.first }
            pad.removeFirst(count)
            commitWord(unique[0].first, pre)
            showCandidates(unique)
            after?.invoke()
        }
        for ((tag, rec) in recs) {
            rec.recognize(inkObj, context)
                .addOnSuccessListener { r ->
                    results[tag] = r.candidates
                        .map { it.text.trim() to it.score }
                        .filter { it.first.isNotEmpty() }
                    done()
                }
                .addOnFailureListener { done() }
        }
    }

    /** The language you're already writing in gets a small head start. */
    private fun preferredLang(pre: String): String {
        val c = pre.lastOrNull { it.isLetter() } ?: return stickyLang
        return if (c.code in 0x0D80..0x0DFF) "si" else if (c.code < 0x0250) "en-US" else stickyLang
    }

    private fun pickLanguage(results: Map<String, List<Pair<String, Float?>>>, preferred: String): String? {
        val tops = results.filterValues { it.isNotEmpty() }.mapValues { it.value[0].second }
        if (tops.isEmpty()) return null
        if (tops.size == 1) return tops.keys.first()
        // ML Kit scores: lower is better. Without scores, stay with the preferred language.
        if (tops.values.any { it == null }) return if (preferred in tops) preferred else tops.keys.first()
        return tops.minByOrNull { (tag, score) ->
            val s = score!!
            if (tag == preferred) (if (s > 0) s * 0.85f else s - 0.5f) else s
        }!!.key
    }

    private fun commitWord(text: String, pre: String) {
        val ic = currentInputConnection ?: return
        val needSpace = pre.isNotEmpty() && !pre.last().isWhitespace() && text.first().isLetterOrDigit()
        val out = (if (needSpace) " " else "") + text
        ic.commitText(out, 1)
        lastCommit = out
        memory.learn(text)
    }

    /** Swap the word just typed for another suggestion. */
    private fun replaceLast(text: String) {
        val ic = currentInputConnection ?: return
        val last = lastCommit ?: return
        val before = ic.getTextBeforeCursor(last.length, 0)?.toString()
        if (before != last) return
        val out = (if (last.startsWith(" ")) " " else "") + text
        ic.beginBatchEdit()
        ic.deleteSurroundingText(last.length, 0)
        ic.commitText(out, 1)
        ic.endBatchEdit()
        lastCommit = out
        memory.learn(text)
    }

    // ---- keys ----
    private fun typeText(t: String) {
        recognizeAll {
            currentInputConnection?.commitText(t, 1)
            forgetLast()
        }
    }

    private fun space() {
        if (anyInk) { recognizeAll(null); return }
        currentInputConnection?.commitText(" ", 1)
        forgetLast()
    }

    private fun backspace() {
        val pad = lastPad?.takeIf { !it.isEmpty } ?: pads.lastOrNull { !it.isEmpty }
        if (pad != null) {
            pad.undoStroke()
            if (pad.isEmpty) cancel(pad) else schedule(pad)
            return
        }
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        forgetLast()
    }

    /** Delete the word before the cursor (and the space before it). */
    private fun deleteWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        ic.deleteSurroundingText(before.length - i, 0)
        forgetLast()
    }

    private fun enter() {
        recognizeAll {
            val ic = currentInputConnection ?: return@recognizeAll
            val ei = currentInputEditorInfo
            val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            val multiLine = ((ei?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (!multiLine && !noEnterAction && action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
            } else {
                ic.commitText("\n", 1)
            }
            forgetLast()
        }
    }

    private fun switchKeyboard() {
        val switched = if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod() else false
        if (!switched) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    private fun toggleSinglish() {
        val on = !prefs.getBoolean(Prefs.SINGLISH, false)
        prefs.edit().putBoolean(Prefs.SINGLISH, on).apply()
        updateSinglishKey()
        showStatus(if (on) "Singlish on: write \"mama gedara yanawa\" → මම ගෙදර යනව"
                   else "Singlish off: English stays English (Sinhala version is in suggestions)")
    }

    private fun updateSinglishKey() {
        val on = prefs.getBoolean(Prefs.SINGLISH, false)
        singlishKey.background = pill(if (on) 0xFF2F6FDE.toInt() else 0xFFFFFFFF.toInt())
        singlishKey.setTextColor(if (on) Color.WHITE else 0xFF1F2A37.toInt())
    }

    private fun forgetLast() { lastCommit = null; showStatus("") }

    // ---- button panel ----
    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) showWriting()
        else recognizeAll { showPanelArea() }
    }

    private fun showWriting() {
        if (!::panel.isInitialized) return
        panel.visibility = View.GONE
        writeArea.visibility = View.VISIBLE
        modeKey.text = "123"
    }

    private fun showPanelArea() {
        writeArea.visibility = View.GONE
        panel.visibility = View.VISIBLE
        modeKey.text = "✍"
        showPanel(panelTab)
    }

    private fun showPanel(tab: String) {
        panelTab = tab
        panelBody.removeAllViews()
        val rowH = px(42)
        when (tab) {
            "123" -> panelBody.addView(grid(listOf(
                "1 2 3 4 5 6 7 8 9 0", "@ # $ % & * - + = /",
                "( ) [ ] { } < > _ \\", "! ? : ; \" ' ~ ` ^ |", "රු. ₹ € £ ° • … · « »"
            ).map { line -> line.split(" ").map { s -> s to { commitRaw(s) } } }, rowH))

            "edit" -> panelBody.addView(grid(listOf(
                listOf("Select all" to { menu(android.R.id.selectAll) }, "Cut" to { menu(android.R.id.cut) },
                       "Copy" to { menu(android.R.id.copy) }, "Paste" to { menu(android.R.id.paste) }),
                listOf("↶ Undo" to { ctrl(KeyEvent.KEYCODE_Z, false) }, "↷ Redo" to { ctrl(KeyEvent.KEYCODE_Z, true) },
                       "⇤ Start" to { keyCode(KeyEvent.KEYCODE_MOVE_HOME) }, "End ⇥" to { keyCode(KeyEvent.KEYCODE_MOVE_END) }),
                listOf("←" to { keyCode(KeyEvent.KEYCODE_DPAD_LEFT) }, "↑" to { keyCode(KeyEvent.KEYCODE_DPAD_UP) },
                       "↓" to { keyCode(KeyEvent.KEYCODE_DPAD_DOWN) }, "→" to { keyCode(KeyEvent.KEYCODE_DPAD_RIGHT) })
            ), px(52)))

            "emoji" -> {
                val emoji = ("😀 😂 🤣 😊 😍 😘 😎 🤔 😅 😢 😭 😡 🥳 😴 🙄 😇 " +
                    "👍 👎 👏 🙏 💪 👌 ✌️ 🤝 👋 🙌 ❤️ 💔 🔥 ⭐ ✨ 🎉 " +
                    "✅ ❌ ⚠️ 💯 📌 📎 📷 🎵 ☕ 🍕 🍰 🎂 🏠 🚗 ✈️ 🌴 " +
                    "☀️ 🌧️ 🌙 🌸 🐶 🐱 ⚽ 🏏 💻 📱 💰 🎁 ⏰ 📅 🇱🇰 🇺🇸").split(" ")
                val rows = emoji.chunked(12).map { r -> r.map { e -> e to { commitRaw(e) } } }
                panelBody.addView(ScrollView(this).apply { addView(grid(rows, rowH)) })
            }

            "clip" -> {
                saveClip()
                val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val clips = memory.clips()
                if (clips.isEmpty()) {
                    list.addView(TextView(this).apply {
                        text = "Things you copy will show up here. Tap one to paste it."
                        textSize = 15f; setTextColor(0xFF7A7468.toInt()); setPadding(px(12), px(12), px(12), px(12))
                    })
                }
                for (c in clips) {
                    list.addView(TextView(this).apply {
                        text = c; textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                        setTextColor(0xFF1F2A37.toInt()); setPadding(px(12), px(10), px(12), px(10))
                        background = pill(0xFFFFFFFF.toInt())
                        setOnClickListener { commitRaw(c) }
                        setOnLongClickListener { memory.removeClip(c); showPanel("clip"); true }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(5) })
                }
                if (clips.isNotEmpty()) list.addView(TextView(this).apply {
                    text = "Long-press an item to remove it"; textSize = 12f
                    setTextColor(0xFF7A7468.toInt()); setPadding(px(12), px(4), px(12), px(4))
                })
                panelBody.addView(ScrollView(this).apply { addView(list) })
            }
        }
    }

    private fun commitRaw(t: String) { currentInputConnection?.commitText(t, 1); forgetLast() }
    private fun menu(id: Int) { currentInputConnection?.performContextMenuAction(id); forgetLast() }
    private fun keyCode(code: Int) { sendDownUpKeyEvents(code); forgetLast() }

    private fun ctrl(code: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val meta = KeyEvent.META_CTRL_ON or (if (shift) KeyEvent.META_SHIFT_ON else 0)
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        forgetLast()
    }

    private fun grid(rows: List<List<Pair<String, () -> Unit>>>, rowH: Int): LinearLayout {
        val g = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in r) line.addView(key(label, 1f, small = label.length > 2) { action() })
            g.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH).apply { bottomMargin = px(4) })
        }
        return g
    }

    // ---- suggestions bar ----
    private fun showStatus(msg: String) {
        if (!::candBar.isInitialized) return
        candBar.removeAllViews()
        if (msg.isEmpty()) return
        candBar.addView(TextView(this).apply {
            text = msg; textSize = 14f; setTextColor(0xFF7A7468.toInt())
            setPadding(px(10), 0, px(10), 0)
        })
    }

    private fun showCandidates(options: List<Pair<String, String>>) {
        candBar.removeAllViews()
        options.take(10).forEachIndexed { i, (word, tag) ->
            candBar.addView(TextView(this).apply {
                text = word; textSize = 17f
                setTextColor(if (i == 0) 0xFF2F6FDE.toInt() else 0xFF1F2A37.toInt())
                if (i == 0) setTypeface(typeface, Typeface.BOLD)
                setPadding(px(14), px(6), px(14), px(6))
                background = pill(0xFFFFFFFF.toInt())
                setOnClickListener {
                    replaceLast(word)
                    stickyLang = tag
                    showCandidates(listOf(word to tag) + options.filter { it.first != word })
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) })
        }
    }

    // ---- view helpers ----
    private fun px(v: Int) = (v * dp).toInt()

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 12f * dp }

    private fun key(label: String, weight: Float, accent: Boolean = false, small: Boolean = false, onTap: () -> Unit) =
        TextView(this).apply {
            text = label; gravity = Gravity.CENTER
            textSize = if (small || (label.length > 1 && label != "⌫" && label != "සිං")) 15f else 20f
            setTextColor(if (accent) Color.WHITE else 0xFF1F2A37.toInt())
            background = pill(if (accent) 0xFF2F6FDE.toInt() else 0xFFFFFFFF.toInt())
            isClickable = true
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                .apply { marginStart = px(3); marginEnd = px(3) }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatKey(label: String, weight: Float, action: () -> Unit) =
        key(label, weight) {}.apply {
            setOnClickListener(null)
            val repeater = object : Runnable {
                override fun run() { action(); handler.postDelayed(this, 60) }
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); handler.postDelayed(repeater, 450) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeater) }
                }
                true
            }
        }
}
