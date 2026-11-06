package com.penkeyboard.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions

/**
 * Google ML Kit on-device handwriting recognizers, one per language.
 * Each model downloads once (~20 MB), then works offline.
 */
object InkEngine {
    enum class State { CHECKING, DOWNLOADING, READY, FAILED }

    data class Lang(val tag: String, val name: String)

    val LANGS = listOf(Lang("en-US", "English"), Lang("si", "Sinhala"))

    /** Recognizers that are ready to use, keyed by language tag. */
    val recognizers = linkedMapOf<String, DigitalInkRecognizer>()
    val states = LANGS.associate { it.tag to State.CHECKING }.toMutableMap()

    private val waiting = mutableListOf<() -> Unit>()
    private val started = mutableSetOf<String>()

    /** Overall state: READY once at least one language works. */
    val state: State
        get() = when {
            recognizers.isNotEmpty() -> State.READY
            states.values.any { it == State.DOWNLOADING } -> State.DOWNLOADING
            states.values.all { it == State.FAILED } -> State.FAILED
            else -> State.CHECKING
        }

    fun ensure(onChange: () -> Unit) {
        waiting.add(onChange)
        onChange()
        for (lang in LANGS) {
            if (recognizers.containsKey(lang.tag) || lang.tag in started) continue
            started.add(lang.tag)
            load(lang)
        }
    }

    private fun load(lang: Lang) {
        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(lang.tag)
        } catch (e: Exception) { null }
        if (id == null) { set(lang, State.FAILED); return }

        val model = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    create(lang, model)
                } else {
                    set(lang, State.DOWNLOADING)
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { create(lang, model) }
                        .addOnFailureListener { set(lang, State.FAILED) }
                }
            }
            .addOnFailureListener { set(lang, State.FAILED) }
    }

    private fun create(lang: Lang, model: DigitalInkRecognitionModel) {
        recognizers[lang.tag] =
            DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        set(lang, State.READY)
    }

    private fun set(lang: Lang, s: State) {
        states[lang.tag] = s
        if (s == State.FAILED) started.remove(lang.tag) // retry next time
        waiting.toList().forEach { it() }
        if (LANGS.all { states[it.tag] == State.READY }) waiting.clear()
    }

    fun nameOf(tag: String) = LANGS.firstOrNull { it.tag == tag }?.name ?: tag
}
