package com.penkeyboard.app

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions

/** Google ML Kit on-device handwriting recognizer. The model (~20 MB) downloads once, then works offline. */
object InkEngine {
    enum class State { CHECKING, DOWNLOADING, READY, FAILED }

    var recognizer: DigitalInkRecognizer? = null
        private set
    var state = State.CHECKING
        private set

    private val waiting = mutableListOf<(State) -> Unit>()
    private var busy = false

    fun ensure(onState: (State) -> Unit) {
        if (recognizer != null) { onState(State.READY); return }
        waiting.add(onState)
        onState(state)
        if (busy) return
        busy = true

        val id = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
        } catch (e: Exception) { null }
        if (id == null) { publish(State.FAILED); return }

        val model = DigitalInkRecognitionModel.builder(id).build()
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    create(model)
                } else {
                    publish(State.DOWNLOADING)
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener { create(model) }
                        .addOnFailureListener { publish(State.FAILED) }
                }
            }
            .addOnFailureListener { publish(State.FAILED) }
    }

    private fun create(model: DigitalInkRecognitionModel) {
        recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        publish(State.READY)
    }

    private fun publish(s: State) {
        state = s
        if (s == State.READY || s == State.FAILED) busy = false
        waiting.toList().forEach { it(s) }
        if (s == State.READY || s == State.FAILED) waiting.clear()
    }
}
