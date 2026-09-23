package com.example.a3d_viewer.assets

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * A single part label extracted from a GLB node's `extras.prop`.
 *
 * [localPosition] is the node's position in **model/root space** (parent transforms baked in).
 * Used only if the live Filament entity cannot be resolved; otherwise the projector
 * reads that node's world translation every frame.
 */
data class PartLabel(
    val nodeIndex: Int,
    val nodeName: String?,
    val text: String,
    val localPosition: FloatArray,
) {
    init {
        require(localPosition.size == 3) { "localPosition must be xyz" }
    }
}

/**
 * Parses the JSON chunk of a binary glTF (.glb) and extracts nodes that declare
 * custom `extras.prop` label text.
 *
 * Parent `translation` / `rotation` / `scale` / `matrix` chains are baked so the
 * returned position is in model-root space (needed for assets like solarsystem.glb
 * where Empty label nodes are children of scene-root nodes).
 */
object GlbLabelParser {

    private const val GLB_MAGIC = 0x46546C67 // 'glTF'
    private const val CHUNK_JSON = 0x4E4F534A // 'JSON'
    private const val HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8

    fun parseFromAsset(assetManager: AssetManager, assetPath: String): List<PartLabel> {
        assetManager.open(assetPath).use { input ->
            return parse(input.readBytes())
        }
    }

    fun parse(glbBytes: ByteArray): List<PartLabel> {
        require(glbBytes.size >= HEADER_BYTES + CHUNK_HEADER_BYTES) {
            "GLB too small (${glbBytes.size} bytes)"
        }
        val buffer = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.int
        require(magic == GLB_MAGIC) { "Not a GLB file (magic=0x${magic.toString(16)})" }
        buffer.int // version
        buffer.int // total length

        val chunkLength = buffer.int
        val chunkType = buffer.int
        require(chunkType == CHUNK_JSON) { "First GLB chunk is not JSON" }
        require(buffer.remaining() >= chunkLength) { "JSON chunk truncated" }

        val jsonBytes = ByteArray(chunkLength)
        buffer.get(jsonBytes)
        val jsonText = String(jsonBytes, StandardCharsets.UTF_8).trimEnd { it <= ' ' }
        return extractLabels(JSONObject(jsonText))
    }

    private fun extractLabels(gltf: JSONObject): List<PartLabel> {
        val nodes = gltf.optJSONArray("nodes") ?: return emptyList()
        val parentOf = IntArray(nodes.length()) { -1 }
        for (i in 0 until nodes.length()) {
            val children = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) {
                val child = children.optInt(c, -1)
                if (child in 0 until nodes.length()) parentOf[child] = i
            }
        }

        val labels = ArrayList<PartLabel>(8)
        val scratch = FloatArray(16)

        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val extras = node.optJSONObject("extras") ?: continue
            if (!extras.has("prop")) continue

            val prop = extras.optString("prop", "").trim()
            if (prop.isEmpty()) continue

            bakeModelSpacePosition(nodes, parentOf, i, scratch)
            labels += PartLabel(
                nodeIndex = i,
                nodeName = if (node.has("name")) node.optString("name") else null,
                text = prop,
                localPosition = floatArrayOf(scratch[12], scratch[13], scratch[14]),
            )
        }
        return labels
    }

    /**
     * Walks parent chain (root → leaf) and multiplies local node matrices so
     * [out] becomes the model-root 4x4 matrix; translation is in columns 12–14.
     */
    private fun bakeModelSpacePosition(
        nodes: JSONArray,
        parentOf: IntArray,
        nodeIndex: Int,
        out: FloatArray,
    ) {
        val chain = ArrayList<Int>(8)
        var cursor = nodeIndex
        while (cursor >= 0) {
            chain.add(cursor)
            cursor = parentOf[cursor]
        }
        identity(out)
        val local = FloatArray(16)
        val temp = FloatArray(16)
        // Root first, then descendants.
        for (i in chain.lastIndex downTo 0) {
            writeLocalMatrix(nodes.getJSONObject(chain[i]), local)
            multiply(out, local, temp)
            System.arraycopy(temp, 0, out, 0, 16)
        }
    }

    private fun writeLocalMatrix(node: JSONObject, out: FloatArray) {
        val matrix = node.optJSONArray("matrix")
        if (matrix != null && matrix.length() >= 16) {
            for (i in 0 until 16) out[i] = matrix.optDouble(i, 0.0).toFloat()
            return
        }

        val t = node.optJSONArray("translation")
        val tx = t?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val ty = t?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val tz = t?.optDouble(2, 0.0)?.toFloat() ?: 0f

        val r = node.optJSONArray("rotation") // glTF quaternion xyzw
        val qx = r?.optDouble(0, 0.0)?.toFloat() ?: 0f
        val qy = r?.optDouble(1, 0.0)?.toFloat() ?: 0f
        val qz = r?.optDouble(2, 0.0)?.toFloat() ?: 0f
        val qw = r?.optDouble(3, 1.0)?.toFloat() ?: 1f

        val s = node.optJSONArray("scale")
        val sx = s?.optDouble(0, 1.0)?.toFloat() ?: 1f
        val sy = s?.optDouble(1, 1.0)?.toFloat() ?: 1f
        val sz = s?.optDouble(2, 1.0)?.toFloat() ?: 1f

        // Column-major TRS = T * R * S
        val xx = qx * qx
        val yy = qy * qy
        val zz = qz * qz
        val xy = qx * qy
        val xz = qx * qz
        val yz = qy * qz
        val wx = qw * qx
        val wy = qw * qy
        val wz = qw * qz

        out[0] = (1f - 2f * (yy + zz)) * sx
        out[1] = (2f * (xy + wz)) * sx
        out[2] = (2f * (xz - wy)) * sx
        out[3] = 0f

        out[4] = (2f * (xy - wz)) * sy
        out[5] = (1f - 2f * (xx + zz)) * sy
        out[6] = (2f * (yz + wx)) * sy
        out[7] = 0f

        out[8] = (2f * (xz + wy)) * sz
        out[9] = (2f * (yz - wx)) * sz
        out[10] = (1f - 2f * (xx + yy)) * sz
        out[11] = 0f

        out[12] = tx
        out[13] = ty
        out[14] = tz
        out[15] = 1f
    }

    private fun identity(m: FloatArray) {
        m.fill(0f)
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    /** Column-major 4x4: out = a * b */
    private fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                out[col * 4 + row] =
                    a[row] * b[col * 4] +
                        a[4 + row] * b[col * 4 + 1] +
                        a[8 + row] * b[col * 4 + 2] +
                        a[12 + row] * b[col * 4 + 3]
            }
        }
    }
}
