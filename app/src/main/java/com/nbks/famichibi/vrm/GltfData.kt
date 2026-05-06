package com.nbks.famichibi.vrm

import android.graphics.Bitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class GltfRoot(
    val meshes: List<GltfMesh>? = null,
    val buffers: List<GltfBuffer>? = null,
    val bufferViews: List<GltfBufferView>? = null,
    val accessors: List<GltfAccessor>? = null,
    val images: List<GltfImage>? = null,
    val textures: List<GltfTexture>? = null,
    val materials: List<GltfMaterial>? = null,
    val nodes: List<GltfNode>? = null,
    val scenes: List<GltfScene>? = null,
)

@Serializable
class GltfScene(val nodes: List<Int>? = null)

@Serializable
class GltfNode(
    val mesh: Int? = null,
    val translation: List<Float>? = null,
    val rotation: List<Float>? = null,
    val scale: List<Float>? = null,
    val children: List<Int>? = null,
    val matrix: List<Float>? = null,
    val name: String? = null,
)

@Serializable
class GltfMesh(val primitives: List<GltfPrimitive>, val name: String? = null)

@Serializable
class GltfPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int? = null,
    val material: Int? = null,
    val mode: Int = 4, // TRIANGLES
)

@Serializable
class GltfBuffer(val uri: String? = null, val byteLength: Int)

@Serializable
class GltfBufferView(
    val buffer: Int,
    val byteOffset: Int = 0,
    val byteLength: Int,
    val byteStride: Int? = null,
    val target: Int? = null,
)

@Serializable
class GltfAccessor(
    val bufferView: Int? = null,
    val byteOffset: Int = 0,
    val componentType: Int,
    val count: Int,
    val type: String,
    val max: List<Float>? = null,
    val min: List<Float>? = null,
)

@Serializable
class GltfImage(
    val uri: String? = null,
    val bufferView: Int? = null,
    val mimeType: String? = null,
    val name: String? = null,
)

@Serializable
class GltfTexture(val source: Int? = null, val name: String? = null)

@Serializable
class GltfMaterial(
    val pbrMetallicRoughness: GltfPbr? = null,
    val alphaMode: String? = null,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
    val name: String? = null,
)

@Serializable
class GltfPbr(
    val baseColorFactor: List<Float>? = null,
    val baseColorTexture: GltfTextureInfo? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
)

@Serializable
class GltfTextureInfo(val index: Int, val texCoord: Int = 0)

data class MeshData(
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray?,
    val indices: IntArray,
    val indexType: Int, // GLES20.GL_UNSIGNED_SHORT or GL_UNSIGNED_INT
    val textureBitmap: Bitmap?,
    val baseColorFactor: FloatArray,
    val doubleSided: Boolean,
)
