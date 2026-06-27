package com.nbks.famichibi.vrm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "com.nbks.famichibi"

data class ParseResult(
    val root: GltfRoot,
    val meshes: List<MeshData>,
    val vrmExtension: VrmExtension?,
)

class GltfParser(private val root: GltfRoot, private val binChunk: ByteArray) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private val parseJson = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(glbBytes: ByteArray): ParseResult? {
            val (jsonObj, bin) = VrmGlbParser.parseGlb(glbBytes) ?: return null
            val root = try {
                parseJson.decodeFromJsonElement(GltfRoot.serializer(), jsonObj)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode GLTF JSON", e)
                return null
            }
            val vrmExt = VrmGlbParser.extractVrmExtension(jsonObj)
            val meshes = GltfParser(root, bin).parseMeshes()
            return ParseResult(root, meshes, vrmExt)
        }
    }

    fun parseMeshes(): List<MeshData> {
        val meshes = mutableListOf<MeshData>()
        val gltfMeshes = root.meshes ?: return meshes

        for ((meshIdx, mesh) in gltfMeshes.withIndex()) {
            for ((primIdx, prim) in mesh.primitives.withIndex()) {
                try {
                    val posAccIdx = prim.attributes["POSITION"] ?: continue
                    val posAcc = root.accessors?.getOrNull(posAccIdx) ?: continue
                    val positions = readFloatArray(posAcc, 3)

                    val uvAccIdx = prim.attributes["TEXCOORD_0"]
                    val uvAcc = uvAccIdx?.let { root.accessors?.getOrNull(it) }
                    val uvs = uvAcc?.let { readFloatArray(it, 2) }

                    val normAccIdx = prim.attributes["NORMAL"]
                    val normAcc = normAccIdx?.let { root.accessors?.getOrNull(it) }
                    val normals = normAcc?.let { readFloatArray(it, 3) }

                    val jointAccIdx = prim.attributes["JOINTS_0"]
                    val jointAcc = jointAccIdx?.let { root.accessors?.getOrNull(it) }
                    val joints = jointAcc?.let { readJointArray(it) }

                    val weightAccIdx = prim.attributes["WEIGHTS_0"]
                    val weightAcc = weightAccIdx?.let { root.accessors?.getOrNull(it) }
                    val weights = weightAcc?.let { readFloatArray(it, 4) }

                    val idxAccIdx = prim.indices ?: continue
                    val idxAcc = root.accessors?.getOrNull(idxAccIdx) ?: continue
                    val (indices, indexType) = readIndexArray(idxAcc)

                    val texture = prim.material?.let { readTexture(it) }
                    val material = prim.material?.let { root.materials?.getOrNull(it) }
                    val baseColorFactor = material?.pbrMetallicRoughness?.baseColorFactor?.toFloatArray()
                        ?: floatArrayOf(1f, 1f, 1f, 1f)
                    val doubleSided = material?.doubleSided ?: false

                    val skinData = meshIdx.let { meshIndex ->
                        val nodeWithSkin = root.nodes?.indexOfFirst { it.mesh == meshIndex && it.skin != null }
                        if (nodeWithSkin != null && nodeWithSkin >= 0) {
                            val skinIdx = root.nodes!![nodeWithSkin].skin ?: return@let null
                            val skin = root.skins?.getOrNull(skinIdx) ?: return@let null
                            val ibmAcc = root.accessors?.getOrNull(skin.inverseBindMatrices) ?: return@let null
                            val ibmArray = readMatrixArray(ibmAcc)
                            SkinData(
                                joints = skin.joints.toIntArray(),
                                inverseBindMatrices = ibmArray,
                            )
                        } else null
                    }

                    meshes.add(
                        MeshData(
                            positions = positions,
                            normals = normals,
                            uvs = uvs,
                            joints = joints,
                            weights = weights,
                            indices = indices,
                            indexType = indexType,
                            textureBitmap = texture,
                            baseColorFactor = baseColorFactor,
                            doubleSided = doubleSided,
                            skin = skinData,
                        )
                    )
                    Log.d(TAG, "Parsed mesh $meshIdx prim $primIdx: ${positions.size / 3} verts, ${indices.size} indices")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse mesh $meshIdx prim $primIdx", e)
                }
            }
        }
        return meshes
    }

    private fun readBufferBytes(bufferViewIdx: Int, accessor: GltfAccessor): ByteArray {
        val bv = root.bufferViews?.getOrNull(bufferViewIdx)
            ?: throw IllegalArgumentException("bufferView $bufferViewIdx not found")
        val buf = root.buffers?.getOrNull(bv.buffer)
            ?: throw IllegalArgumentException("buffer ${bv.buffer} not found")

        val sourceBytes = if (buf.uri == null) {
            binChunk
        } else {
            throw IllegalArgumentException("External buffer URI not supported: ${buf.uri}")
        }

        val offset = bv.byteOffset + accessor.byteOffset
        val componentSize = componentTypeSize(accessor.componentType)
        val numComponents = typeNumComponents(accessor.type)
        val elementSize = numComponents * componentSize
        val stride = bv.byteStride ?: elementSize
        val totalBytes = stride * (accessor.count - 1) + elementSize

        if (offset + totalBytes > sourceBytes.size) {
            throw IllegalArgumentException("Buffer overflow: offset=$offset, total=$totalBytes, source=${sourceBytes.size}")
        }

        if (bv.byteStride == null || bv.byteStride == elementSize) {
            return sourceBytes.copyOfRange(offset, offset + totalBytes)
        }

        // De-interleave
        val result = ByteArray(totalBytes)
        for (i in 0 until accessor.count) {
            System.arraycopy(sourceBytes, offset + i * stride, result, i * elementSize, elementSize)
        }
        return result
    }

    private fun readFloatArray(accessor: GltfAccessor, expectedComponents: Int): FloatArray {
        val bvIdx = accessor.bufferView ?: throw IllegalArgumentException("Accessor missing bufferView")
        val bytes = readBufferBytes(bvIdx, accessor)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = accessor.count * expectedComponents
        val result = FloatArray(count)
        when (accessor.componentType) {
            5126 -> { // FLOAT
                for (i in 0 until count) result[i] = buffer.getFloat()
            }
            5121 -> { // UNSIGNED_BYTE normalized
                for (i in 0 until count) result[i] = (buffer.get().toInt() and 0xFF) / 255f
            }
            5123 -> { // UNSIGNED_SHORT normalized
                for (i in 0 until count) result[i] = (buffer.getShort().toInt() and 0xFFFF) / 65535f
            }
            else -> throw IllegalArgumentException("Unsupported componentType for float array: ${accessor.componentType}")
        }
        return result
    }

    private fun readJointArray(accessor: GltfAccessor): FloatArray {
        val bvIdx = accessor.bufferView ?: throw IllegalArgumentException("Accessor missing bufferView")
        val bytes = readBufferBytes(bvIdx, accessor)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = accessor.count * 4
        val result = FloatArray(count)
        when (accessor.componentType) {
            5121 -> { // UNSIGNED_BYTE
                for (i in 0 until count) result[i] = (buffer.get().toInt() and 0xFF).toFloat()
            }
            5123 -> { // UNSIGNED_SHORT
                for (i in 0 until count) result[i] = (buffer.getShort().toInt() and 0xFFFF).toFloat()
            }
            else -> throw IllegalArgumentException("Unsupported joint componentType: ${accessor.componentType}")
        }
        return result
    }

    private fun readMatrixArray(accessor: GltfAccessor): FloatArray {
        val bvIdx = accessor.bufferView ?: throw IllegalArgumentException("Accessor missing bufferView")
        val bytes = readBufferBytes(bvIdx, accessor)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val numMatrices = accessor.count
        val result = FloatArray(numMatrices * 16)
        for (i in 0 until numMatrices * 16) {
            result[i] = buffer.getFloat()
        }
        return result
    }

    private fun readIndexArray(accessor: GltfAccessor): Pair<IntArray, Int> {
        val bvIdx = accessor.bufferView ?: throw IllegalArgumentException("Accessor missing bufferView")
        val bytes = readBufferBytes(bvIdx, accessor)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = IntArray(accessor.count)
        when (accessor.componentType) {
            5121 -> { // UNSIGNED_BYTE
                for (i in 0 until accessor.count) result[i] = buffer.get().toInt() and 0xFF
                return result to android.opengl.GLES20.GL_UNSIGNED_BYTE
            }
            5123 -> { // UNSIGNED_SHORT
                for (i in 0 until accessor.count) result[i] = buffer.getShort().toInt() and 0xFFFF
                return result to android.opengl.GLES20.GL_UNSIGNED_SHORT
            }
            5125 -> { // UNSIGNED_INT
                for (i in 0 until accessor.count) result[i] = buffer.getInt()
                return result to android.opengl.GLES20.GL_UNSIGNED_INT
            }
            else -> throw IllegalArgumentException("Unsupported index componentType: ${accessor.componentType}")
        }
    }

    private fun readTexture(materialIdx: Int): Bitmap? {
        val material = root.materials?.getOrNull(materialIdx) ?: return null
        val texInfo = material.pbrMetallicRoughness?.baseColorTexture ?: return null
        val texture = root.textures?.getOrNull(texInfo.index) ?: return null
        val imageIdx = texture.source ?: return null
        val image = root.images?.getOrNull(imageIdx) ?: return null

        return if (image.uri != null) {
            // External URI not supported in this context
            null
        } else if (image.bufferView != null) {
            val bv = root.bufferViews?.getOrNull(image.bufferView) ?: return null
            val buf = root.buffers?.getOrNull(bv.buffer) ?: return null
            val sourceBytes = if (buf.uri == null) binChunk else return null
            val offset = bv.byteOffset
            val length = bv.byteLength
            if (offset + length > sourceBytes.size) return null
            BitmapFactory.decodeByteArray(sourceBytes, offset, length)
        } else {
            null
        }
    }

    private fun componentTypeSize(type: Int): Int = when (type) {
        5120, 5121 -> 1 // BYTE, UNSIGNED_BYTE
        5122, 5123 -> 2 // SHORT, UNSIGNED_SHORT
        5125, 5126 -> 4 // UNSIGNED_INT, FLOAT
        else -> throw IllegalArgumentException("Unknown componentType: $type")
    }

    private fun typeNumComponents(type: String): Int = when (type) {
        "SCALAR" -> 1
        "VEC2" -> 2
        "VEC3" -> 3
        "VEC4" -> 4
        "MAT2" -> 4
        "MAT3" -> 9
        "MAT4" -> 16
        else -> throw IllegalArgumentException("Unknown type: $type")
    }
}
