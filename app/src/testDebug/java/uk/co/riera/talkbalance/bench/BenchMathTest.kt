package uk.co.riera.talkbalance.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchMathTest {
    @Test fun identicalVectorsScoreOne() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(1f, cosineSimilarity(v, v), 1e-6f)
    }

    @Test fun orthogonalVectorsScoreZero() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test fun oppositeVectorsScoreMinusOne() {
        assertEquals(-1f, cosineSimilarity(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-6f)
    }

    @Test fun zeroVectorScoresZeroNotNaN() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 2f)), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedLengthThrows() {
        cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
    }

    @Test fun csvHasSixFieldsWithAllRefs() {
        val line = formatBenchCsv(1000L, 1500L, 42L, listOf(0.912f, 0.334f, -0.051f))
        assertEquals("1000,1500,42,0.912,0.334,-0.051", line)
    }

    @Test fun csvLeavesMissingRefsEmpty() {
        val line = formatBenchCsv(2000L, 800L, 30L, listOf(0.5f, null, null))
        assertEquals("2000,800,30,0.500,,", line)
        assertEquals(6, line.split(",").size)
    }

    @Test fun csvWithNoRefsStillHasThreeTrailingFields() {
        val line = formatBenchCsv(3000L, 900L, 25L, listOf(null, null, null))
        assertEquals("3000,900,25,,,", line)
        assertEquals(6, line.split(",").size)
    }

    @Test fun initLineFormat() {
        assertEquals(
            "init model=wespeaker_en_voxceleb_CAM++_LM.onnx dim=512 initMs=137",
            formatBenchInit("wespeaker_en_voxceleb_CAM++_LM.onnx", 512, 137),
        )
    }

    @Test fun modelFilesMatchAssets() {
        assertEquals("wespeaker_en_voxceleb_CAM++_LM.onnx", BenchModel.CAMPP.file)
        assertEquals("3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx", BenchModel.ERES2NET.file)
        assertTrue(BenchModel.entries.size == 2)
    }
}
