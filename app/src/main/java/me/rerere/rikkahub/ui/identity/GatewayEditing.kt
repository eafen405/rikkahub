package me.rerere.rikkahub.ui.identity

import me.rerere.rikkahub.data.remote.GatewayRouteModel

/**
 * A model list as typed, plus the 1-based lines that could not be read.
 *
 * [invalidLines] is not an error message: it is the fact the page reports. A
 * line whose limit is not a whole number is named by its number rather than
 * quietly dropped, so a save never stores something the user did not write.
 */
data class ModelsText(
    val models: List<GatewayRouteModel>,
    val invalidLines: List<Int> = emptyList(),
)

private const val FIELD_SEPARATOR = '|'

/**
 * One model per line, fields separated by `|`:
 *
 * ```
 * MiniMax-M2 | MiniMax M2 | 200000 | 32768 | text,image
 * minimax/other
 * ```
 *
 * A field left blank is unset, not zero: the backend then applies the route's
 * own default. An unreadable number is reported by line and left unset, which
 * blocks the save through [ModelsText.invalidLines] rather than sending a
 * limit nobody chose.
 */
fun encodeModelsText(models: List<GatewayRouteModel>): String = models.joinToString("\n") { model ->
    listOf(
        model.id,
        model.name.orEmpty(),
        model.contextWindow?.toString().orEmpty(),
        model.maxTokens?.toString().orEmpty(),
        model.input?.joinToString(",").orEmpty(),
    ).dropLastWhile { it.isEmpty() }.joinToString(" $FIELD_SEPARATOR ")
}

fun decodeModelsText(text: String): ModelsText {
    val models = mutableListOf<GatewayRouteModel>()
    val invalid = mutableListOf<Int>()

    text.lines().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEachIndexed
        val lineNumber = index + 1
        val fields = line.split(FIELD_SEPARATOR).map { it.trim() }
        val id = fields.firstOrNull().orEmpty()
        if (id.isEmpty()) {
            invalid += lineNumber
            return@forEachIndexed
        }
        val contextWindow = fields.getOrNull(2).orEmpty().toIntOrNull()
        val maxTokens = fields.getOrNull(3).orEmpty().toIntOrNull()
        // A non-blank field that did not parse is a line the user must fix; a
        // blank one is simply unset.
        if (fields.getOrNull(2).orEmpty().isNotEmpty() && contextWindow == null) invalid += lineNumber
        if (fields.getOrNull(3).orEmpty().isNotEmpty() && maxTokens == null) invalid += lineNumber
        models += GatewayRouteModel(
            id = id,
            name = fields.getOrNull(1).orEmpty().takeIf { it.isNotEmpty() },
            contextWindow = contextWindow,
            maxTokens = maxTokens,
            input = fields.getOrNull(4).orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() },
        )
    }

    return ModelsText(models = models, invalidLines = invalid.distinct())
}
