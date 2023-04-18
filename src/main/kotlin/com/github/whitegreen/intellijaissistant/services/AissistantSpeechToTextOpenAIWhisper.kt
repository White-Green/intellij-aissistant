package com.github.whitegreen.intellijaissistant.services

import com.intellij.openapi.components.service
import com.intellij.util.io.toByteArray
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.mime.content.ByteArrayBody
import org.apache.http.impl.client.HttpClients
import org.jetbrains.zip.signer.utils.isLittleEndian
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

class VoicedSignalExtractor {
    private var calcVarLength = 24000 / 10
    private var calcVarStatLength = 24000 / 10 * 3

    private var varSignalSum = 0L
    private var varSignalSquaredSum = 0L
    private val varSignalQueue = ArrayDeque<Short>()

    private var largeVarianceCount = 0
    private val largeVarianceQueue = ArrayDeque<Boolean>()

    private val allSignalQueue = ArrayDeque<Short>()

    private var voiced = false

    private val voicedSignal = ArrayDeque<ShortArray>()
    private val voicedSignalLock = ReentrantLock(true)

    fun reset(samplingRate: Int) {
        calcVarLength = samplingRate / 10
        calcVarStatLength = samplingRate / 10 * 3
        varSignalSum = 0
        varSignalSquaredSum = 0
        varSignalQueue.clear()
        largeVarianceCount = 0
        largeVarianceQueue.clear()
        allSignalQueue.clear()
        voiced = false
    }

    fun addSignal(signal: ShortArray) {
        for (item in signal) {
            varSignalQueue.add(item)
            varSignalSum += item
            varSignalSquaredSum += item.toLong() * item

            val squaredMean = varSignalSum.toDouble() / varSignalQueue.size
            val variance = varSignalSquaredSum.toDouble() / varSignalQueue.size - squaredMean * squaredMean
            // 閾値500,000ぐらいで実装するとよさそう
            largeVarianceQueue.add(variance >= 500_000)
            if (variance >= 500_000) largeVarianceCount++

            allSignalQueue.add(item)

            if (voiced) {
                if (largeVarianceCount.toDouble() / calcVarStatLength < 0.3) {
                    println("end voiced")
                    voiced = false
                    voicedSignalLock.withLock {
                        voicedSignal.add(allSignalQueue.toShortArray())
                    }
                }
            } else {
                if (largeVarianceCount.toDouble() / calcVarStatLength > 0.7) {
                    println("begin voiced")
                    voiced = true
                }
            }

            while (varSignalQueue.size >= calcVarLength) {
                val first = varSignalQueue.removeFirst()
                varSignalSum -= first
                varSignalSquaredSum -= first.toLong() * first
            }
            while (largeVarianceQueue.size >= calcVarStatLength) {
                val first = largeVarianceQueue.removeFirst()
                if (first) largeVarianceCount--
            }
            if (!voiced) {
                while (allSignalQueue.size >= calcVarLength + calcVarStatLength) allSignalQueue.removeFirst()
            }
        }
    }

    fun getVoicedSignal(): ShortArray? {
        return voicedSignalLock.withLock {
            voicedSignal.removeFirstOrNull()
        }
    }
}

private fun encodeToWav(voicedSignal: ShortArray, samplingRate: Float): ByteArray {
    val buffer = ByteBuffer.allocate(voicedSignal.size * 2).apply {
        for (value in voicedSignal) {
            putShort(value)
        }
    }
    val audioInputStream = AudioInputStream(
        ByteArrayInputStream(buffer.toByteArray()), AudioFormat(
            samplingRate,
            16,
            1,
            true,
            !buffer.isLittleEndian()
        ), voicedSignal.size.toLong()
    )
    val byteArrayOutputStream = ByteArrayOutputStream()
    AudioSystem.write(
        audioInputStream,
        AudioFileFormat.Type.WAVE,
        byteArrayOutputStream
    )
    return byteArrayOutputStream.toByteArray()
}

class AissistantSpeechToTextOpenAIWhisper : SpeechToText {
    private val samplingRate = AtomicInteger(24000)
    private val reset = AtomicBoolean(false)
    private val signalQueue = ArrayDeque<Short>()

    private val signalLock = ReentrantLock(true)
    private val signalCondition = signalLock.newCondition()

    private val textQueue = ArrayDeque<String>()
    private val textQueueLock = ReentrantLock(true)

    init {
        thread {
            val voicedSignalExtractor = VoicedSignalExtractor()
            val httpClient = HttpClients.createDefault()
            while (true) {
                val additionalSignal = signalLock.withLock {
                    while (signalQueue.isEmpty()) signalCondition.await()
                    signalQueue.toShortArray().also { signalQueue.clear() }
                }
                if (reset.compareAndSet(true, false)) {
                    voicedSignalExtractor.reset(samplingRate.get())
                }
                voicedSignalExtractor.addSignal(additionalSignal)
                while (true) {
                    val voicedSignal = voicedSignalExtractor.getVoicedSignal() ?: break
                    val wavData = encodeToWav(voicedSignal, samplingRate.get().toFloat())

                    val request = HttpPost("https://api.openai.com/v1/audio/transcriptions").apply {
                        addHeader("Authorization", "Bearer ${service<OpenAIToken>().get()}")
                        entity = MultipartEntityBuilder.create()
                            .addTextBody("model", "whisper-1")
                            .addTextBody("language", "ja") // FIXME: OPTION
                            .addTextBody("response_format", "text")
                            .addPart("file", ByteArrayBody(wavData, ContentType.create("audio/wav"), "audio.wav"))
                            .build()
                    }

                    val response = httpClient.execute(request)
                    val responseText = IOUtils.toString(response.entity.content, "utf-8")
                    textQueueLock.withLock {
                        textQueue.add(responseText)
                    }
                }
            }
        }
    }

    override fun reset(samplingRate: Float) {
        this.samplingRate.set(samplingRate.roundToInt())
        this.reset.set(true)
    }

    override fun addSpeechSignal(data: ShortArray, len: Int) {
        signalLock.withLock {
            for (i in 0 until data.count().coerceAtMost(len)) {
                signalQueue.add(data[i])
            }
            signalCondition.signal()
        }
    }

    override fun getText(): String? {
        textQueueLock.withLock {
            return textQueue.removeFirstOrNull()
        }
    }
}