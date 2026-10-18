package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.data.remote.GatewayRouteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text form the route editor uses for a model list.
 *
 * The wire accepts a model list, but a phone has no room for a five-field row
 * per model. The page shows one model per line — `id | name | context window |
 * max tokens | input modalities` — so this codec is the seam between what the
 * user typed and the list the backend is asked to store.
 *
 * The important promise is not that it accepts everything, but that it never
 * silently invents or drops a value: a limit that is not a whole number is
 * reported by its line and blocks the save, instead of being quietly sent as
 * "no limit".
 */
class GatewayEditingTest {

    @Test
    fun `a model list round-trips through the text form`() {
        val models = listOf(
            GatewayRouteModel(
                id = "MiniMax-M2",
                name = "MiniMax M2",
                contextWindow = 200_000,
                maxTokens = 32_768,
                input = listOf("text", "image"),
            ),
            GatewayRouteModel(id = "minimax/other"),
        )

        val decoded = decodeModelsText(encodeModelsText(models))

        assertEquals(models, decoded.models)
        assertEquals(emptyList<Int>(), decoded.invalidLines)
    }

    @Test
    fun `blank lines are ignored and empty optional fields stay unset`() {
        val decoded = decodeModelsText(
            """
            minimax/M2 | MiniMax M2

            minimax/other |
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                GatewayRouteModel(id = "minimax/M2", name = "MiniMax M2"),
                GatewayRouteModel(id = "minimax/other"),
            ),
            decoded.models,
        )
        assertEquals(emptyList<Int>(), decoded.invalidLines)
    }

    @Test
    fun `a limit that is not a whole number is reported by its line instead of being dropped`() {
        val decoded = decodeModelsText("m2 | MiniMax M2 | not-a-number | 512")

        assertEquals(listOf(1), decoded.invalidLines)
        assertEquals(512, decoded.models.single().maxTokens)
        assertEquals("the invalid limit is not invented", null, decoded.models.single().contextWindow)
    }

    @Test
    fun `a line without a model id is reported rather than silently skipped`() {
        val decoded = decodeModelsText(" | a name without an id")

        assertTrue(decoded.invalidLines.contains(1))
        assertEquals(emptyList<GatewayRouteModel>(), decoded.models)
    }
}
