package com.penkeyboard.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea

class PenKeyboardService : InputMethodService(), InkView.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ink: InkView
    private lateinit var candBar: LinearLayout
    private var lastCommit: String? = null
    private var recognizing = false
    private val dp by lazy { resources.displayMetrics.density }
    private val prefs by lazy { getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }

    private val autoRecognize = Runnable { recognize(null) }

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
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candBar)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(46)))

        // Writing pad
        ink = InkView(this, this).apply {
            background = GradientDrawable().apply {
                setColor(0xFFFFFDF8.toInt()); cornerRadius = 14f * dp
                setStroke(px(1), 0xFFDDD5C7.toInt())
            }
        }
        root.addView(ink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            px(prefs.getInt(Prefs.PAD_HEIGHT, 230))).apply { bottomMargin = px(6) })

        // Key row
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(key("⌨", 1f) { switchKeyboard() })
        for (p in listOf(",", ".", "?", "!", "'")) row.addView(key(p, 0.8f) { typePunct(p) })
        row.addView(key("space", 4f) { space() })
        row.addView(repeatKey("⌫", 1.3f) { backspace() })
        row.addView(key("↵", 1.3f, accent = true) { enter() })
        row.addView(key("⌄", 1f) { requestHideSelf(0) })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)))

        showStatus("Getting handwriting ready…")
        InkEngine.ensure { onEngineState() }
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!::ink.isInitialized) return
        ink.penOnly = prefs.getBoolean(Prefs.PEN_ONLY, true)
        ink.clear()
        lastCommit = null
        handler.removeCallbacks(autoRecognize)
        InkEngine.ensure { onEngineState() }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        handler.removeCallbacks(autoRecognize)
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

    // ---- InkView.Listener ----
    override fun onStrokeStart() { handler.removeCallbacks(autoRecognize) }

    override fun onStrokeEnd() {
        val stroke = ink.strokes.lastOrNull() ?: return
        val others = ink.strokes.dropLast(1)
        when (val g = Gestures.classify(stroke, others, ink.width.toFloat(), dp)) {
            is Gestures.G.EraseStrokes -> {
                ink.removeStrokes(g.strokes + listOf(stroke))
                if (ink.isEmpty) handler.removeCallbacks(autoRecognize) else scheduleRecognize()
            }
            Gestures.G.DeleteWord -> { ink.removeStrokes(listOf(stroke)); deleteWord() }
            Gestures.G.Space -> { ink.removeStrokes(listOf(stroke)); space() }
            Gestures.G.Backspace -> { ink.removeStrokes(listOf(stroke)); backspace() }
            Gestures.G.Enter -> { ink.removeStrokes(listOf(stroke)); enter() }
            Gestures.G.None -> scheduleRecognize()
        }
    }

    override fun onErase() { if (!ink.isEmpty) scheduleRecognize() }

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

    private fun scheduleRecognize() {
        handler.removeCallbacks(autoRecognize)
        handler.postDelayed(autoRecognize, prefs.getInt(Prefs.DELAY_MS, 700).toLong())
    }

    /** Recognize what's on the pad, type it, then run [after] (e.g. press Enter). */
    private fun recognize(after: (() -> Unit)?) {
        handler.removeCallbacks(autoRecognize)
        if (ink.isEmpty) { after?.invoke(); return }
        if (recognizing) { handler.postDelayed({ recognize(after) }, 120); return }
        val recs = InkEngine.recognizers.toMap()
        if (recs.isEmpty()) {
            InkEngine.ensure { onEngineState() }
            return
        }
        val ic = currentInputConnection ?: return
        val pre = ic.getTextBeforeCursor(20, 0)?.toString() ?: ""
        val context = RecognitionContext.builder()
            .setPreContext(pre)
            .setWritingArea(WritingArea(ink.width.toFloat(), ink.height.toFloat()))
            .build()
        val count = ink.strokes.size
        val inkObj = ink.toInk(count)
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
            results[winner]!!.take(5).forEach { options.add(it.first to winner) }
            results.filterKeys { it != winner }.forEach { (tag, list) ->
                list.take(3).forEach { options.add(it.first to tag) }
            }
            val unique = options.distinctBy { it.first }
            ink.removeFirst(count)
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

    /** The language used last in this text box gets a small head start. */
    private var stickyLang = "en-US"

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
    }

    // ---- keys ----
    private fun space() {
        if (!ink.isEmpty) { recognize(null); return }
        currentInputConnection?.commitText(" ", 1)
        forgetLast()
    }

    private fun typePunct(p: String) {
        recognize {
            currentInputConnection?.commitText(p, 1)
            forgetLast()
        }
    }

    private fun backspace() {
        if (!ink.isEmpty) {
            ink.undoStroke()
            if (ink.isEmpty) handler.removeCallbacks(autoRecognize) else scheduleRecognize()
            return
        }
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        forgetLast()
    }

    private fun enter() {
        recognize {
            val ic = currentInputConnection ?: return@recognize
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

    private fun forgetLast() { lastCommit = null; showStatus("") }

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
        options.take(9).forEachIndexed { i, (word, tag) ->
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

    private fun key(label: String, weight: Float, accent: Boolean = false, onTap: () -> Unit) =
        TextView(this).apply {
            text = label; gravity = Gravity.CENTER; textSize = if (label.length > 1 && label != "⌫") 15f else 20f
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
