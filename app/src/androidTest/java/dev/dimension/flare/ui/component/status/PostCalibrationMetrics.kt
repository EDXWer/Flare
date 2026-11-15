package dev.dimension.flare.ui.component.status

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density

internal data class CalibrationText(
    val role: String,
    val bounds: Rect,
    val layout: TextLayoutResult,
)

internal fun postCalibrationJson(
    density: Density,
    themeBody: TextStyle,
    container: Rect,
    timestamp: String,
    texts: List<CalibrationText>,
): String =
    """
    {
      "widthDp":402,
      "density":${density.density},
      "fontScale":${density.fontScale},
      "timestamp":${timestamp.jsonQuoted()},
      "containerBoundsPx":${container.jsonBounds()},
      "themeBody":${themeBody.jsonTypography()},
      "postBody":${texts.first { it.role == "body" }.layout.layoutInput.style.jsonTypography()},
      "texts":[${texts.joinToString(",") { text ->
        val layout = text.layout
        val input = layout.layoutInput
        val bounds = text.bounds.translate(-container.left, -container.top)
        """{
          "role":${text.role.jsonQuoted()},
          "text":${input.text.text.jsonQuoted()},
          "boundsInContainerPx":${bounds.jsonBounds()},
          "typography":${input.style.jsonTypography()},
          "colorArgb":${input.style.color.toArgb()},
          "lines":[${(0 until layout.lineCount).joinToString(",") { line ->
            """{"start":${layout.getLineStart(
                line,
            )},"end":${layout.getLineEnd(
                line,
            )},"baselinePx":${layout.getLineBaseline(
                line,
            )},"leftPx":${layout.getLineLeft(
                line,
            )},"widthPx":${layout.getLineRight(
                line,
            ) - layout.getLineLeft(line)},"topPx":${layout.getLineTop(line)},"bottomPx":${layout.getLineBottom(line)}}"""
        }}],
          "characters":[${input.text.indices.joinToString(",") { offset ->
            """{"utf16Offset":$offset,"codeUnit":${input.text[offset].code},"boundsPx":${layout.getBoundingBox(offset).jsonBounds()}}"""
        }}]
        }"""
    }}]
    }
    """.trimIndent()

private fun TextStyle.jsonTypography(): String =
    """{"fontSizeSp":${fontSize.value.takeIf {
        it.isFinite()
    }},"lineHeightSp":${lineHeight.value.takeIf { it.isFinite() }},"letterSpacing":${letterSpacing.value.takeIf {
        it
            .isFinite()
    }},"letterSpacingUnit":${letterSpacing.type.toString().jsonQuoted()},"fontWeight":${fontWeight?.weight}}"""

private fun Rect.jsonBounds(): String = "[$left,$top,$right,$bottom]"

private fun String.jsonQuoted(): String =
    "\"" +
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
