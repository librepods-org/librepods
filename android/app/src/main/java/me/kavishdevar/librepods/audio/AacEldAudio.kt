/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

data class AacEldAccessUnit(val timestamp: Long, val data: ByteArray)

/** Parses AACP 0x58/subtype 0x0001 microphone SDUs without reading past malformed frames. */
object AacEldPacketParser {
    private const val HEADER_SIZE = 22

    fun isAudioPacket(packet: ByteArray): Boolean =
        packet.size >= 8 &&
            packet[0] == 0x04.toByte() &&
            packet[2] == 0x04.toByte() &&
            packet[4] == 0x58.toByte() &&
            packet[5] == 0x00.toByte() &&
            packet[6] == 0x01.toByte() &&
            packet[7] == 0x00.toByte()

    fun parse(packet: ByteArray): List<AacEldAccessUnit> {
        if (!isAudioPacket(packet) || packet.size < HEADER_SIZE) return emptyList()

        val frames = mutableListOf<AacEldAccessUnit>()
        var offset = HEADER_SIZE
        while (offset + 5 <= packet.size) {
            val timestamp = ByteBuffer.wrap(packet, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
                .toLong() and 0xffffffffL
            val length = packet[offset + 4].toInt() and 0xff
            val start = offset + 5
            val end = start + length
            if (end > packet.size) break
            frames += AacEldAccessUnit(timestamp, packet.copyOfRange(start, end))
            offset = end
        }
        return frames
    }
}

/**
 * Decodes the AirPods AAC-ELD stream on a dedicated bounded worker.
 * PCM callbacks run on that worker and must return promptly. The decoder reports its actual output
 * sample rate because observed implementations distinguish the 48 kHz coding rate from a 64 kHz
 * presentation rate.
 */
class AacEldDecoder(
    private val listener: Listener,
    private val codecFactory: () -> MediaCodec = {
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    },
) : AutoCloseable {
    interface Listener {
        fun onPcmData(data: ByteArray, sampleRate: Int, channelCount: Int)
        fun onDecoderError(message: String, cause: Throwable? = null)
    }

    private val queue = ArrayBlockingQueue<AacEldAccessUnit>(QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var codec: MediaCodec? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            codec = codecFactory().also { decoder ->
                val format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    AAC_CODING_RATE,
                    CHANNEL_COUNT,
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(AUDIO_SPECIFIC_CONFIG))
                }
                decoder.configure(format, null, null, 0)
                decoder.start()
            }
            worker = Thread(::decodeLoop, "librepods-aac-eld").also { it.start() }
            true
        } catch (error: Throwable) {
            running.set(false)
            releaseCodec()
            listener.onDecoderError("AAC-ELD decoder is unavailable", error)
            false
        }
    }

    fun offer(packet: ByteArray) {
        if (!running.get()) return
        for (frame in AacEldPacketParser.parse(packet)) {
            if (!queue.offer(frame)) {
                queue.poll()
                queue.offer(frame)
            }
        }
    }

    private fun decodeLoop() {
        var presentationTimeUs = 0L
        try {
            while (running.get()) {
                val frame = queue.take()
                val decoder = codec ?: break
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    decoder.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(frame.data)
                    }
                    decoder.queueInputBuffer(inputIndex, 0, frame.data.size, presentationTimeUs, 0)
                    presentationTimeUs += FRAME_DURATION_US
                }
                drain(decoder)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (running.get()) listener.onDecoderError("AAC-ELD decode failed", error)
        } finally {
            releaseCodec()
        }
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    decoder.getOutputBuffer(outputIndex)?.let { output ->
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        output.get(pcm)
                        val format = decoder.outputFormat
                        listener.onPcmData(
                            pcm,
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        )
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    @Synchronized
    private fun releaseCodec() {
        val decoder = codec ?: return
        codec = null
        runCatching { decoder.stop() }
        decoder.release()
    }

    companion object {
        private const val AAC_CODING_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val FRAME_DURATION_US = 7_500L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val QUEUE_CAPACITY = 256
        private val AUDIO_SPECIFIC_CONFIG = byteArrayOf(
            0xF8.toByte(), 0xE6.toByte(), 0x30.toByte(), 0x00.toByte(),
        )
    }
}
