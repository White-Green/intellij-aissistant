package com.github.whitegreen.intellijaissistant.services

import com.intellij.openapi.components.service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.*
import kotlin.concurrent.thread

interface SpeechToText {
    fun reset(samplingRate: Float)
    fun addSpeechSignal(data: ShortArray, len: Int)
    fun getText(): String?
}

class AissistantMicrophoneInputService : AissistantMicrophoneInput {
    var thread: Thread? = null
    var running: Boolean = false

    override fun startRecording() {
        if (running) return
        running = true
        thread = thread {
            val format = AudioFormat(24000f, 16, 1, true, true)
            val info = DataLine.Info(TargetDataLine::class.java, format)

            if (!AudioSystem.isLineSupported(info)) {
                println("Line not supported")
                return@thread
            }

            val targetLine = AudioSystem.getLine(info) as TargetDataLine
            targetLine.use {
                service<SpeechToText>().reset(24000f)
                it.open()
                it.start()
                service<AissistantUI>().onRecordingStarted()
                val buffer = ByteArray(format.sampleRate.toInt() / 10 * format.sampleSizeInBits / 8)
                val data = ShortArray(format.sampleRate.toInt() / 10)
                val audioStream = AudioInputStream(it)
                var len: Int
                while (audioStream.read(buffer).also { len = it } > 0 && running) {
                    ByteBuffer.wrap(buffer, 0, len)
                        .order(if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(data, 0, len / (format.sampleSizeInBits / 8))
                    val speechToText = service<SpeechToText>()
                    speechToText.addSpeechSignal(data, len / (format.sampleSizeInBits / 8))
                    var text: String? = speechToText.getText()
                    while (text != null) {
                        service<AissistantUI>().addTextForActiveUI(text.trim())
                        text = speechToText.getText()
                    }
                }
                it.stop()
                it.close()
            }
            service<AissistantUI>().onRecordingStopped()
        }
    }

    override fun stopRecording() {
        running = false
        thread = null
    }
}