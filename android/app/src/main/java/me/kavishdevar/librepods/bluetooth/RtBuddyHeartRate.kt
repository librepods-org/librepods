/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.bluetooth

import android.os.SystemClock

/** A validated heart-rate sample decoded from an RTBuddy SensorDataWX frame. */
data class HeartRateSample(
    val bpm: Int,
    val sequence: Int,
    val receivedAtMillis: Long,
    val receivedAtElapsedRealtime: Long = SystemClock.elapsedRealtime()
)

internal enum class HeartRateRejectionReason {
    UNSUPPORTED_LOG_TYPE,
    MISSING_HEART_RATE_PAYLOAD,
    UNRECOGNIZED_HEART_RATE_PAYLOAD
}

internal data class HeartRateDecodeResult(
    val samples: List<HeartRateSample> = emptyList(),
    val relatedFrameCount: Int = 0,
    val rejectionReasons: Map<HeartRateRejectionReason, Int> = emptyMap(),
    val suppressRawLogging: Boolean = false,
    val passthroughPackets: List<ByteArray> = emptyList()
) {
    val rejectedFrameCount: Int
        get() = rejectionReasons.values.sum()
}

internal data class HeartRateServiceResolution(
    val serviceId: Int?,
    val discoveredFromMetadata: Boolean
)

internal suspend fun waitForHeartRateServiceResolution(
    decoder: RtBuddyHeartRateDecoder,
    timeoutMillis: Long,
    elapsedRealtimeMillis: () -> Long,
    pause: suspend (Long) -> Unit
): Boolean {
    val deadline = elapsedRealtimeMillis() + timeoutMillis.coerceAtLeast(0L)
    while (true) {
        val resolution = decoder.heartRateServiceResolution()
        if (resolution.discoveredFromMetadata) return true
        if (elapsedRealtimeMillis() >= deadline) return resolution.serviceId != null
        pause(25L)
    }
}

/**
 * Reassembles RTBuddy frames and extracts the verified HEARTRATE SensorDataWX payload.
 *
 * The parser deliberately keeps the protocol checks that prevent control/startup frames from being
 * interpreted as BPM: live log type, the metadata-advertised HeartRateService, exact 18-byte
 * payload, known status trailer, and the validated physiological range. Service 19 remains a
 * legacy fallback only when metadata has not assigned it to another accessory service.
 */
internal class RtBuddyHeartRateDecoder(
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime
) {
    private var carry = ByteArray(0)
    private var discoveredHeartRateServiceId: Int? = null
    private val explicitlyNonHeartRateServiceIds = mutableSetOf<Int>()

    @Synchronized
    fun reset() {
        carry = ByteArray(0)
        discoveredHeartRateServiceId = null
        explicitlyNonHeartRateServiceIds.clear()
    }

    /** The service to target for this connection, or null when metadata rules out the fallback. */
    @Synchronized
    fun heartRateServiceIdForControl(): Int? =
        discoveredHeartRateServiceId
            ?: LEGACY_HEART_RATE_SERVICE.takeUnless(explicitlyNonHeartRateServiceIds::contains)

    @Synchronized
    fun discoveredHeartRateServiceId(): Int? = discoveredHeartRateServiceId

    @Synchronized
    fun heartRateServiceResolution(): HeartRateServiceResolution {
        val discovered = discoveredHeartRateServiceId
        return HeartRateServiceResolution(
            serviceId = discovered
                ?: LEGACY_HEART_RATE_SERVICE.takeUnless(explicitlyNonHeartRateServiceIds::contains),
            discoveredFromMetadata = discovered != null
        )
    }

    @Synchronized
    fun feed(chunk: ByteArray): HeartRateDecodeResult {
        if (chunk.isEmpty()) return HeartRateDecodeResult()

        val hadCarry = carry.isNotEmpty()
        val carryWasSensitive = carry.size >= MIN_SENSITIVE_PREFIX_LENGTH
        val data = if (carry.isEmpty()) chunk else carry + chunk
        carry = ByteArray(0)

        val samples = mutableListOf<HeartRateSample>()
        val passthroughPackets = mutableListOf<ByteArray>()
        val rejectionReasons = mutableMapOf<HeartRateRejectionReason, Int>()
        var relatedFrameCount = 0
        var suppressRawLogging = carryWasSensitive
        var cursor = 0

        while (cursor < data.size) {
            val frameOffset = data.indexOfPrefix(RTBUDDY_FRAME_PREFIX, cursor)
            if (frameOffset < 0) {
                val suffixLength = data.longestSuffixMatchingPrefix(
                    prefix = RTBUDDY_FRAME_PREFIX,
                    startIndex = cursor
                )
                val passthroughEnd = data.size - suffixLength
                if (passthroughEnd > cursor) {
                    passthroughPackets += data.copyOfRange(cursor, passthroughEnd)
                }
                if (suffixLength > 0) {
                    carry = data.copyOfRange(passthroughEnd, data.size)
                    suppressRawLogging = suppressRawLogging ||
                        suffixLength >= MIN_SENSITIVE_PREFIX_LENGTH
                }
                break
            }

            if (frameOffset > cursor) {
                passthroughPackets += data.copyOfRange(cursor, frameOffset)
            }
            if (data.size - frameOffset < AACP_RTBUDDY_HEADER_LENGTH) {
                carry = data.copyOfRange(frameOffset, data.size)
                suppressRawLogging = true
                break
            }

            val payloadLength = data.readLe16(frameOffset + 10)
            if (payloadLength > MAX_RTBUDDY_PAYLOAD_LENGTH) {
                // The exact SensorDataWX prefix is sensitive, but the declared length is untrusted.
                suppressRawLogging = true
                break
            }

            val frameLength = AACP_RTBUDDY_HEADER_LENGTH + payloadLength
            if (data.size - frameOffset < frameLength) {
                carry = data.copyOfRange(frameOffset, data.size)
                suppressRawLogging = true
                break
            }

            val frame = data.copyOfRange(frameOffset, frameOffset + frameLength)
            val classification = classifyFrame(frame)
            if (classification.related || classification.consumed) {
                if (classification.related) {
                    relatedFrameCount++
                    classification.rejectionReason?.let { rejectionReasons.increment(it) }
                    classification.sample?.let(samples::add)
                }
                suppressRawLogging = true
            } else {
                passthroughPackets += frame
                if (hadCarry && frameOffset == 0) suppressRawLogging = true
            }
            cursor = frameOffset + frameLength
        }

        return HeartRateDecodeResult(
            samples = samples,
            relatedFrameCount = relatedFrameCount,
            rejectionReasons = rejectionReasons,
            suppressRawLogging = suppressRawLogging,
            passthroughPackets = passthroughPackets
        )
    }

    private fun classifyFrame(frame: ByteArray): FrameClassification {
        val topLevel = parseProtoMessage(
            frame,
            AACP_RTBUDDY_HEADER_LENGTH,
            frame.size
        ) ?: return FrameClassification()

        val metadataRecords = updateServiceMetadata(frame, topLevel)

        val sequence = topLevel.firstVarint(FIELD_SEQUENCE)?.toInt() ?: -1
        val logType = topLevel.firstVarint(FIELD_LOG_TYPE)?.toInt() ?: -1
        val commands = mutableListOf<HeartRateCommand>()

        topLevel.fields.forEach { field ->
            if (field.wireType == WIRE_LENGTH_DELIMITED &&
                field.number in SENSOR_DATA_COMMAND_FIELDS &&
                MetadataRecord(field.valueStart, field.valueEnd) !in metadataRecords &&
                commands.size < MAX_COMMANDS_PER_FRAME
            ) {
                collectHeartRateCommands(
                    data = frame,
                    start = field.valueStart,
                    end = field.valueEnd,
                    depth = 0,
                    commands = commands
                )
            }
        }

        if (commands.isEmpty()) {
            return FrameClassification(consumed = metadataRecords.isNotEmpty())
        }
        if (logType !in LIVE_SENSOR_DATA_LOG_TYPES) {
            return FrameClassification(
                related = true,
                rejectionReason = HeartRateRejectionReason.UNSUPPORTED_LOG_TYPE
            )
        }

        val payloads = commands.flatMap { it.payloadCandidates }
        val acceptedPayload = payloads.firstOrNull(::isValidHeartRatePayload)
            ?: return FrameClassification(
                related = true,
                rejectionReason = if (payloads.isEmpty()) {
                    HeartRateRejectionReason.MISSING_HEART_RATE_PAYLOAD
                } else {
                    HeartRateRejectionReason.UNRECOGNIZED_HEART_RATE_PAYLOAD
                }
            )

        return FrameClassification(
            related = true,
            sample = HeartRateSample(
                bpm = acceptedPayload.unsignedByteAt(HEART_RATE_BPM_OFFSET),
                sequence = sequence,
                receivedAtMillis = wallClockMillis(),
                receivedAtElapsedRealtime = elapsedRealtimeMillis()
            )
        )
    }

    private fun updateServiceMetadata(
        data: ByteArray,
        topLevel: ProtoMessage
    ): Set<MetadataRecord> {
        val metadataRecords = mutableSetOf<MetadataRecord>()
        topLevel.fields.forEach { field ->
            if (field.wireType != WIRE_LENGTH_DELIMITED) return@forEach
            val serviceRecord = parseProtoMessage(data, field.valueStart, field.valueEnd)
                ?: return@forEach
            val serviceId = serviceRecord.firstVarint(FIELD_SERVICE)?.toInt()
                ?: return@forEach
            val metadataFields = serviceRecord.fields.filter {
                it.number == FIELD_SERVICE_METADATA && it.wireType == WIRE_LENGTH_DELIMITED
            }
            if (metadataFields.isEmpty()) return@forEach

            val identifiesHeartRate = metadataFields.any { metadata ->
                data.containsBytes(
                    needle = HEART_RATE_SERVICE_MARKER,
                    start = metadata.valueStart,
                    end = metadata.valueEnd
                )
            }
            val identifiesHostLibHid = metadataFields.any { metadata ->
                data.containsBytes(
                    needle = HOST_LIB_HID_MARKER,
                    start = metadata.valueStart,
                    end = metadata.valueEnd
                )
            }

            if (identifiesHeartRate || identifiesHostLibHid) {
                metadataRecords += MetadataRecord(field.valueStart, field.valueEnd)
            }
            when {
                // A service explicitly named HostLibHID must never be targeted as heart rate.
                identifiesHostLibHid -> {
                    explicitlyNonHeartRateServiceIds += serviceId
                    if (discoveredHeartRateServiceId == serviceId) {
                        discoveredHeartRateServiceId = null
                    }
                }

                identifiesHeartRate && serviceId !in explicitlyNonHeartRateServiceIds -> {
                    discoveredHeartRateServiceId = serviceId
                }
            }
        }
        return metadataRecords
    }

    private fun collectHeartRateCommands(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        commands: MutableList<HeartRateCommand>
    ) {
        if (depth > MAX_COMMAND_ENVELOPE_DEPTH || commands.size >= MAX_COMMANDS_PER_FRAME) return
        val message = parseProtoMessage(data, start, end) ?: return
        val service = message.firstVarint(FIELD_SERVICE)?.toInt()

        if (service != null && isHeartRateService(service)) {
            val payloads = mutableListOf<ByteArray>()
            message.fields.forEach { field ->
                if (field.number == FIELD_COMMAND_PAYLOAD &&
                    field.wireType == WIRE_LENGTH_DELIMITED
                ) {
                    collectPayloadCandidates(
                        data = data,
                        start = field.valueStart,
                        end = field.valueEnd,
                        depth = 0,
                        candidates = payloads
                    )
                }
            }
            commands += HeartRateCommand(payloads)
        }

        if (depth == MAX_COMMAND_ENVELOPE_DEPTH) return
        message.fields.forEach { field ->
            if (field.wireType == WIRE_LENGTH_DELIMITED &&
                commands.size < MAX_COMMANDS_PER_FRAME
            ) {
                collectHeartRateCommands(
                    data = data,
                    start = field.valueStart,
                    end = field.valueEnd,
                    depth = depth + 1,
                    commands = commands
                )
            }
        }
    }

    private fun isHeartRateService(serviceId: Int): Boolean {
        val discovered = discoveredHeartRateServiceId
        if (discovered != null) return serviceId == discovered
        if (serviceId in explicitlyNonHeartRateServiceIds) return false
        return serviceId == LEGACY_HEART_RATE_SERVICE
    }

    private fun collectPayloadCandidates(
        data: ByteArray,
        start: Int,
        end: Int,
        depth: Int,
        candidates: MutableList<ByteArray>
    ) {
        if (candidates.size >= MAX_PAYLOAD_CANDIDATES_PER_COMMAND) return

        val direct = data.copyOfRange(start, end)
        if (candidates.none(direct::contentEquals)) candidates += direct
        if (depth >= MAX_PAYLOAD_WRAPPER_DEPTH) return

        val wrapper = parseProtoMessage(data, start, end) ?: return
        wrapper.fields.forEach { field ->
            if (field.wireType == WIRE_LENGTH_DELIMITED &&
                candidates.size < MAX_PAYLOAD_CANDIDATES_PER_COMMAND
            ) {
                collectPayloadCandidates(
                    data = data,
                    start = field.valueStart,
                    end = field.valueEnd,
                    depth = depth + 1,
                    candidates = candidates
                )
            }
        }
    }

    private fun isValidHeartRatePayload(payload: ByteArray): Boolean {
        if (payload.size != HEART_RATE_PAYLOAD_LENGTH) return false
        if (payload.unsignedByteAt(HEART_RATE_BPM_OFFSET) !in MIN_BPM..MAX_BPM) return false

        return KNOWN_HEART_RATE_STATUS_TAILS.any { tail ->
            tail.indices.all { index ->
                payload[HEART_RATE_STATUS_TAIL_OFFSET + index] == tail[index]
            }
        }
    }

    private fun parseProtoMessage(data: ByteArray, start: Int, end: Int): ProtoMessage? {
        if (start < 0 || end < start || end > data.size || end - start > MAX_PROTO_MESSAGE_LENGTH) {
            return null
        }

        val fields = mutableListOf<ProtoField>()
        var index = start
        while (index < end) {
            if (fields.size >= MAX_PROTO_FIELDS) return null
            val key = readVarint(data, index, end) ?: return null
            index = key.nextIndex

            val fieldNumber = key.value ushr 3
            if (fieldNumber <= 0 || fieldNumber > MAX_PROTO_FIELD_NUMBER) return null
            val wireType = (key.value and 0x07).toInt()

            when (wireType) {
                WIRE_VARINT -> {
                    val value = readVarint(data, index, end) ?: return null
                    fields += ProtoField(
                        number = fieldNumber.toInt(),
                        wireType = wireType,
                        varintValue = value.value,
                        valueStart = index,
                        valueEnd = value.nextIndex
                    )
                    index = value.nextIndex
                }

                WIRE_LENGTH_DELIMITED -> {
                    val length = readVarint(data, index, end) ?: return null
                    if (length.value > Int.MAX_VALUE) return null
                    val valueEnd = length.nextIndex + length.value.toInt()
                    if (valueEnd < length.nextIndex || valueEnd > end) return null

                    fields += ProtoField(
                        number = fieldNumber.toInt(),
                        wireType = wireType,
                        valueStart = length.nextIndex,
                        valueEnd = valueEnd
                    )
                    index = valueEnd
                }

                WIRE_FIXED64 -> {
                    if (end - index < 8) return null
                    fields += ProtoField(
                        number = fieldNumber.toInt(),
                        wireType = wireType,
                        valueStart = index,
                        valueEnd = index + 8
                    )
                    index += 8
                }

                WIRE_FIXED32 -> {
                    if (end - index < 4) return null
                    fields += ProtoField(
                        number = fieldNumber.toInt(),
                        wireType = wireType,
                        valueStart = index,
                        valueEnd = index + 4
                    )
                    index += 4
                }

                else -> return null
            }
        }
        return ProtoMessage(fields)
    }

    private fun readVarint(data: ByteArray, start: Int, end: Int): VarintRead? {
        var value = 0L
        var shift = 0
        var index = start

        while (index < end && shift < 64) {
            val byte = data[index++].toInt().and(0xFF)
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return VarintRead(value, index)
            shift += 7
        }
        return null
    }

    private fun ByteArray.unsignedByteAt(index: Int): Int = this[index].toInt().and(0xFF)

    private fun <K> MutableMap<K, Int>.increment(key: K) {
        this[key] = getOrDefault(key, 0) + 1
    }

    private data class HeartRateCommand(val payloadCandidates: List<ByteArray>)

    private data class MetadataRecord(val start: Int, val end: Int)

    private data class ProtoMessage(val fields: List<ProtoField>) {
        fun firstVarint(fieldNumber: Int): Long? = fields.firstOrNull {
            it.number == fieldNumber && it.wireType == WIRE_VARINT
        }?.varintValue
    }

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val varintValue: Long? = null,
        val valueStart: Int,
        val valueEnd: Int
    )

    private data class VarintRead(val value: Long, val nextIndex: Int)

    private data class FrameClassification(
        val related: Boolean = false,
        val consumed: Boolean = false,
        val sample: HeartRateSample? = null,
        val rejectionReason: HeartRateRejectionReason? = null
    )

    private companion object {
        const val AACP_RTBUDDY_HEADER_LENGTH = 12
        const val MAX_RTBUDDY_PAYLOAD_LENGTH = 16 * 1024
        const val MIN_SENSITIVE_PREFIX_LENGTH = 5

        // Firmware has emitted live records with both log types and different exact status trailers
        // depending on whether one or both earbuds participate in the session.
        val LIVE_SENSOR_DATA_LOG_TYPES = setOf(1, 3)
        val KNOWN_HEART_RATE_STATUS_TAILS = arrayOf(
            byteArrayOf(0x10, 0x00, 0x00),
            byteArrayOf(0x10, 0x00, 0x80.toByte()),
            byteArrayOf(0x20, 0x00, 0x00),
            byteArrayOf(0x20, 0x80.toByte(), 0x00),
            byteArrayOf(0x20, 0x02, 0x80.toByte()),
            byteArrayOf(0x20, 0x82.toByte(), 0x80.toByte())
        )

        const val FIELD_SEQUENCE = 1
        const val FIELD_LOG_TYPE = 2
        const val FIELD_SERVICE = 1
        const val FIELD_SERVICE_METADATA = 2
        const val FIELD_COMMAND_PAYLOAD = 3
        const val LEGACY_HEART_RATE_SERVICE = 19
        const val HEART_RATE_PAYLOAD_LENGTH = 18
        const val HEART_RATE_BPM_OFFSET = 1
        const val HEART_RATE_STATUS_TAIL_OFFSET = 15
        const val MIN_BPM = 30
        const val MAX_BPM = 220

        val SENSOR_DATA_COMMAND_FIELDS = setOf(5, 7, 8, 9, 12)
        const val MAX_COMMAND_ENVELOPE_DEPTH = 3
        const val MAX_PAYLOAD_WRAPPER_DEPTH = 3
        const val MAX_COMMANDS_PER_FRAME = 16
        const val MAX_PAYLOAD_CANDIDATES_PER_COMMAND = 12
        const val MAX_PROTO_MESSAGE_LENGTH = MAX_RTBUDDY_PAYLOAD_LENGTH
        const val MAX_PROTO_FIELDS = 96
        const val MAX_PROTO_FIELD_NUMBER = 4_096L

        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH_DELIMITED = 2
        const val WIRE_FIXED32 = 5

        // type=0x0004, service=0x0004, opcode=0x0017, descriptor=0x00100000
        val RTBUDDY_FRAME_PREFIX = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x17, 0x00,
            0x00, 0x00, 0x10, 0x00
        )

        val HEART_RATE_SERVICE_MARKER = "HeartRateService".encodeToByteArray()
        val HOST_LIB_HID_MARKER = "HostLibHID".encodeToByteArray()
    }
}

/** Builds RTBuddy heart-rate controls with a per-connection service and monotonic sequence. */
internal class RtBuddyHeartRateControlFrames(
    private val initialSequence: Int = LEGACY_INITIAL_SEQUENCE
) {
    private var nextSequence = initialSequence

    @Synchronized
    fun reset() {
        nextSequence = initialSequence
    }

    @Synchronized
    fun start(serviceId: Int): ByteArray =
        buildFrame(serviceId, takeSequence(), HEART_RATE_INTERVAL_MICROS)

    @Synchronized
    fun stop(serviceId: Int): ByteArray =
        buildFrame(serviceId, takeSequence(), 0)

    private fun takeSequence(): Int {
        val sequence = nextSequence
        nextSequence = if (sequence == Int.MAX_VALUE) 0 else sequence + 1
        return sequence
    }

    companion object {
        private const val LEGACY_INITIAL_SEQUENCE = 9_059
        private const val HEART_RATE_INTERVAL_MICROS = 1_000_000

        private val RTBUDDY_CONTROL_PREFIX = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x17, 0x00,
            0x00, 0x00, 0x10, 0x00
        )

        internal fun buildFrame(serviceId: Int, sequence: Int, intervalMicros: Int): ByteArray {
            require(serviceId in 1..4_096) { "Invalid RTBuddy service ID: $serviceId" }
            require(sequence >= 0) { "RTBuddy sequence must be non-negative" }
            require(intervalMicros >= 0) { "Heart-rate interval must be non-negative" }

            val setting = byteArrayOf(0x01) + intervalMicros.toLittleEndian32()
            val command =
                protoVarintField(1, serviceId) +
                    protoVarintField(2, 2) +
                    protoBytesField(3, setting)
            val body =
                protoVarintField(1, sequence) +
                    protoBytesField(8, command)
            require(body.size <= 0xFFFF) { "RTBuddy control body is too large" }

            return RTBUDDY_CONTROL_PREFIX + body.size.toLittleEndian16() + body
        }

        internal fun isControlFrame(packet: ByteArray): Boolean {
            if (packet.size < RTBUDDY_CONTROL_PREFIX.size + 2 + 7) return false
            if (!RTBUDDY_CONTROL_PREFIX.indices.all {
                    packet[it] == RTBUDDY_CONTROL_PREFIX[it]
                }
            ) return false
            return packet[packet.lastIndex - 6] == 0x1A.toByte() &&
                packet[packet.lastIndex - 5] == 0x05.toByte() &&
                packet[packet.lastIndex - 4] == 0x01.toByte()
        }

        private fun protoVarintField(fieldNumber: Int, value: Int): ByteArray =
            encodeVarint((fieldNumber shl 3).toLong()) + encodeVarint(value.toLong())

        private fun protoBytesField(fieldNumber: Int, value: ByteArray): ByteArray =
            encodeVarint(((fieldNumber shl 3) or 2).toLong()) +
                encodeVarint(value.size.toLong()) +
                value

        private fun encodeVarint(value: Long): ByteArray {
            require(value >= 0) { "Varints must be non-negative" }
            var remaining = value
            val result = ArrayList<Byte>(10)
            do {
                var byte = (remaining and 0x7F).toInt()
                remaining = remaining ushr 7
                if (remaining != 0L) byte = byte or 0x80
                result += byte.toByte()
            } while (remaining != 0L)
            return result.toByteArray()
        }

        private fun Int.toLittleEndian16(): ByteArray = byteArrayOf(
            and(0xFF).toByte(),
            ushr(8).and(0xFF).toByte()
        )

        private fun Int.toLittleEndian32(): ByteArray = byteArrayOf(
            and(0xFF).toByte(),
            ushr(8).and(0xFF).toByte(),
            ushr(16).and(0xFF).toByte(),
            ushr(24).and(0xFF).toByte()
        )
    }
}

internal data class HeartRateControlSendResult(
    val attempted: Boolean,
    val sent: Boolean,
    val serviceId: Int? = null,
    val discoveredFromMetadata: Boolean = false
)

/**
 * Owns the heart-rate service pin and control sequence for one AACP connection.
 *
 * A stop is only emitted after a start was successfully written. Failed stops retain the pin so a
 * retry cannot be redirected by metadata that arrived after the stream began.
 */
internal class RtBuddyHeartRateControlSession(
    private val decoder: RtBuddyHeartRateDecoder,
    private val frames: RtBuddyHeartRateControlFrames = RtBuddyHeartRateControlFrames()
) {
    private var activeServiceId: Int? = null
    private var activeServiceWasDiscovered = false

    @Synchronized
    fun sendStart(sender: (ByteArray) -> Boolean): HeartRateControlSendResult =
        sendControl(start = true, sender = sender)

    @Synchronized
    fun sendStop(sender: (ByteArray) -> Boolean): HeartRateControlSendResult =
        sendControl(start = false, sender = sender)

    @Synchronized
    fun reset() {
        activeServiceId = null
        activeServiceWasDiscovered = false
        frames.reset()
    }

    private fun sendControl(
        start: Boolean,
        sender: (ByteArray) -> Boolean
    ): HeartRateControlSendResult {
        val resolution = decoder.heartRateServiceResolution()
        val serviceId = if (start) {
            activeServiceId ?: resolution.serviceId?.also {
                activeServiceId = it
                activeServiceWasDiscovered = resolution.discoveredFromMetadata
            }
        } else {
            activeServiceId
        } ?: return HeartRateControlSendResult(attempted = false, sent = false)

        val packet = if (start) frames.start(serviceId) else frames.stop(serviceId)
        val discoveredFromMetadata = activeServiceWasDiscovered
        val sent = sender(packet)
        when {
            start && !sent && activeServiceId == serviceId -> {
                activeServiceId = null
                activeServiceWasDiscovered = false
            }

            !start && sent && activeServiceId == serviceId -> {
                activeServiceId = null
                activeServiceWasDiscovered = false
            }
        }
        return HeartRateControlSendResult(
            attempted = true,
            sent = sent,
            serviceId = serviceId,
            discoveredFromMetadata = discoveredFromMetadata
        )
    }
}

private fun ByteArray.readLe16(offset: Int): Int =
    this[offset].toInt().and(0xFF) or (this[offset + 1].toInt().and(0xFF) shl 8)

private fun ByteArray.indexOfPrefix(prefix: ByteArray, startIndex: Int): Int {
    if (prefix.isEmpty()) return startIndex.coerceAtMost(size)
    val lastStart = size - prefix.size
    if (startIndex > lastStart) return -1

    for (start in startIndex.coerceAtLeast(0)..lastStart) {
        if (prefix.indices.all { this[start + it] == prefix[it] }) return start
    }
    return -1
}

private fun ByteArray.longestSuffixMatchingPrefix(
    prefix: ByteArray,
    startIndex: Int
): Int {
    val available = size - startIndex.coerceIn(0, size)
    val maxLength = minOf(available, prefix.size - 1)
    for (length in maxLength downTo 1) {
        val start = size - length
        if ((0 until length).all { this[start + it] == prefix[it] }) return length
    }
    return 0
}

private fun ByteArray.containsBytes(needle: ByteArray, start: Int, end: Int): Boolean {
    if (needle.isEmpty()) return true
    if (start < 0 || end > size || start > end || end - start < needle.size) return false
    val lastStart = end - needle.size
    for (candidate in start..lastStart) {
        if (needle.indices.all { this[candidate + it] == needle[it] }) return true
    }
    return false
}
