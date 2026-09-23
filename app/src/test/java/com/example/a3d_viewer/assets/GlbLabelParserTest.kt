package com.example.a3d_viewer.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Validates GLB JSON-chunk parsing against synthetic glTF and real assets when present.
 */
class GlbLabelParserTest {

    @Test
    fun parsesExtrasPropAndTranslationFromSyntheticGlb() {
        val gltfJson = """
            {
              "asset":{"version":"2.0"},
              "nodes":[
                {"name":"meshRoot"},
                {
                  "name":"Empty.001",
                  "translation":[1.5, 2.25, -0.5],
                  "extras":{"prop":"Filament"}
                }
              ]
            }
        """.trimIndent()
        val labels = GlbLabelParser.parse(buildGlb(gltfJson))
        assertEquals(1, labels.size)
        assertEquals("Filament", labels[0].text)
        assertEquals("Empty.001", labels[0].nodeName)
        assertEquals(1, labels[0].nodeIndex)
        assertEquals(1.5f, labels[0].localPosition[0], 1e-5f)
        assertEquals(2.25f, labels[0].localPosition[1], 1e-5f)
        assertEquals(-0.5f, labels[0].localPosition[2], 1e-5f)
    }

    @Test
    fun parsesMatrixTranslationFallback() {
        val gltfJson = """
            {
              "asset":{"version":"2.0"},
              "nodes":[
                {
                  "name":"Empty.002",
                  "matrix":[1,0,0,0, 0,1,0,0, 0,0,1,0, 3,4,5,1],
                  "extras":{"prop":"Glass Bulb"}
                }
              ]
            }
        """.trimIndent()
        val labels = GlbLabelParser.parse(buildGlb(gltfJson))
        assertEquals(1, labels.size)
        assertEquals(3f, labels[0].localPosition[0], 1e-5f)
        assertEquals(4f, labels[0].localPosition[1], 1e-5f)
        assertEquals(5f, labels[0].localPosition[2], 1e-5f)
    }

    @Test
    fun bakesParentTranslationIntoModelSpace() {
        val gltfJson = """
            {
              "asset":{"version":"2.0"},
              "nodes":[
                {"name":"parent","translation":[10,0,0],"children":[1]},
                {
                  "name":"Empty.001",
                  "translation":[1,2,3],
                  "extras":{"prop":"Mercury"}
                }
              ]
            }
        """.trimIndent()
        val labels = GlbLabelParser.parse(buildGlb(gltfJson))
        assertEquals(1, labels.size)
        assertEquals(11f, labels[0].localPosition[0], 1e-4f)
        assertEquals(2f, labels[0].localPosition[1], 1e-4f)
        assertEquals(3f, labels[0].localPosition[2], 1e-4f)
    }

    @Test
    fun parsesRealBulbAssetWhenAvailable() {
        val bulb = File("src/main/assets/Bulb.glb")
        if (!bulb.exists()) return
        val labels = GlbLabelParser.parse(bulb.readBytes())
        assertTrue(labels.isNotEmpty())
        assertTrue(labels.any { it.text == "Filament" })
        assertTrue(labels.all { it.localPosition.size == 3 })
    }

    private fun buildGlb(json: String): ByteArray {
        val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
        val paddedLen = (jsonBytes.size + 3) and 3.inv()
        val totalLen = 12 + 8 + paddedLen
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x46546C67) // glTF
        buffer.putInt(2) // version
        buffer.putInt(totalLen)
        buffer.putInt(paddedLen)
        buffer.putInt(0x4E4F534A) // JSON
        buffer.put(jsonBytes)
        repeat(paddedLen - jsonBytes.size) { buffer.put(0x20) }
        return buffer.array()
    }
}
