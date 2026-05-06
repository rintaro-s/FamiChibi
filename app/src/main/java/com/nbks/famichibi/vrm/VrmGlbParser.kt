package com.nbks.famichibi.vrm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

object VrmGlbParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseGlb(glbBytes: ByteArray): Pair<JsonObject, ByteArray>? {
        if (glbBytes.size < 12) return null
        val buffer = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4).apply { buffer.get(this) }
        if (!magic.contentEquals(byteArrayOf('g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte()))) {
            return null
        }

        val version = buffer.int
        if (version != 2) return null

        val length = buffer.int
        if (length != glbBytes.size) {
            // continue anyway
        }

        if (buffer.remaining() < 8) return null

        val jsonChunkLength = buffer.int
        val jsonChunkType = buffer.int
        if (jsonChunkType != 0x4E4F534A) return null // "JSON"

        if (buffer.remaining() < jsonChunkLength) return null
        val jsonBytes = ByteArray(jsonChunkLength)
        buffer.get(jsonBytes)

        val jsonObj = try {
            json.parseToJsonElement(jsonBytes.decodeToString()).jsonObject
        } catch (e: Exception) {
            return null
        }

        // Padding
        while (buffer.position() % 4 != 0) {
            if (buffer.remaining() < 1) break
            buffer.get()
        }

        if (buffer.remaining() < 8) return jsonObj to ByteArray(0)

        val binChunkLength = buffer.int
        val binChunkType = buffer.int
        if (binChunkType != 0x004E4942) return jsonObj to ByteArray(0)

        if (buffer.remaining() < binChunkLength) return jsonObj to ByteArray(0)
        val binBytes = ByteArray(binChunkLength)
        buffer.get(binBytes)
        return jsonObj to binBytes
    }

    fun parseJson(glbBytes: ByteArray): JsonObject? {
        return parseGlb(glbBytes)?.first
    }

    fun extractVrmExtension(jsonObj: JsonObject): VrmExtension? {
        val extensions = jsonObj["extensions"]?.jsonObject ?: return null
        val vrmJson = extensions["VRM"] ?: extensions["VRMC_vrm"] ?: return null
        return try {
            json.decodeFromJsonElement<VrmExtension>(vrmJson)
        } catch (e: Exception) {
            null
        }
    }

    fun extractGlbBinaryChunk(glbBytes: ByteArray): ByteArray? {
        return parseGlb(glbBytes)?.second
    }
}
