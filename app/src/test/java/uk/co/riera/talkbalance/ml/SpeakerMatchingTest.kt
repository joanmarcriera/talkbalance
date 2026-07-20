package uk.co.riera.talkbalance.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeakerMatchingTest {

    // --- cosineSimilarity --------------------------------------------------------------------

    @Test fun cosineSimilarityOfIdenticalVectorsIsOne() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, cosineSimilarity(a, a.copyOf()), 1e-5f)
    }

    @Test fun cosineSimilarityOfOrthogonalVectorsIsZero() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0f, cosineSimilarity(a, b), 1e-5f)
    }

    @Test fun cosineSimilarityOfOppositeVectorsIsMinusOne() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        assertEquals(-1f, cosineSimilarity(a, b), 1e-5f)
    }

    @Test fun cosineSimilarityIsZeroWhenAVectorIsAllZero() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0f, cosineSimilarity(a, b), 1e-5f)
    }

    @Test fun cosineSimilarityRejectsMismatchedLengths() {
        assertThrows(IllegalArgumentException::class.java) {
            cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 2f))
        }
    }

    // --- centroid ------------------------------------------------------------------------------

    @Test fun centroidOfASingleEmbeddingIsItself() {
        val embedding = floatArrayOf(1f, 2f, 3f)
        val result = centroid(arrayOf(embedding))
        assertEquals(listOf(1f, 2f, 3f), result.toList())
    }

    @Test fun centroidAveragesMultipleEmbeddingsElementWise() {
        val embeddings = arrayOf(
            floatArrayOf(1f, 0f, 3f),
            floatArrayOf(3f, 2f, 5f),
            floatArrayOf(2f, 4f, 1f),
        )
        val result = centroid(embeddings)
        assertEquals(listOf(2f, 2f, 3f), result.toList())
    }

    @Test fun centroidRejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) {
            centroid(emptyArray())
        }
    }

    @Test fun centroidRejectsMismatchedEmbeddingSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            centroid(arrayOf(floatArrayOf(1f, 2f), floatArrayOf(1f)))
        }
    }

    // --- bestCentroidSimilarity ------------------------------------------------------------------

    @Test fun bestCentroidSimilarityPicksTheHighestMatchAmongSeveralCentroids() {
        val embedding = floatArrayOf(1f, 0f)
        val centroids = listOf(
            floatArrayOf(0f, 1f), // orthogonal -> 0
            floatArrayOf(1f, 0f), // identical -> 1
            floatArrayOf(-1f, 0f), // opposite -> -1
        )
        assertEquals(1f, bestCentroidSimilarity(embedding, centroids), 1e-5f)
    }

    @Test fun bestCentroidSimilarityIsZeroWhenNobodyIsEnrolled() {
        assertEquals(0f, bestCentroidSimilarity(floatArrayOf(1f, 2f), emptyList()), 1e-5f)
    }
}
