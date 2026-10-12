package com.rokid.context.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors

object BarChartMath {
    /** Height of each bar as a fraction of the tallest; all zeros (or empty) → all 0f; negatives count as 0. */
    fun fractions(values: List<Int>): List<Float> {
        val max = values.maxOfOrNull { it.coerceAtLeast(0) } ?: 0
        return values.map { if (max == 0) 0f else it.coerceAtLeast(0).toFloat() / max }
    }

    /** Bar under a tap at [x] in a chart [width] px wide with [count] equal slots; null outside the chart. */
    fun indexAt(x: Float, width: Float, count: Int): Int? =
        if (count <= 0 || width <= 0f || x < 0f || x > width) null else (x / (width / count)).toInt().coerceIn(0, count - 1)

    fun describe(values: List<Int>, labels: List<String>): String =
        "Bar chart: " + values.indices.joinToString(", ") { i -> "${labels.getOrNull(i)?.ifBlank { null } ?: "${i + 1}"} ${values[i]}" }
}

/** Frames per day (spec §2.4 Activity Week/Month). Zero days draw a 3 dp hairline so the axis stays readable. */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    highlightIndex: Int? = null,
    onBarClick: ((Int) -> Unit)? = null,
) {
    val fractions = BarChartMath.fractions(values)
    val description = BarChartMath.describe(values, labels)
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }
                .then(
                    if (onBarClick == null) Modifier else Modifier.pointerInput(values, onBarClick) {
                        detectTapGestures { o -> BarChartMath.indexAt(o.x, size.width.toFloat(), values.size)?.let(onBarClick) }
                    },
                ),
        ) {
            if (fractions.isEmpty()) return@Canvas
            val slot = size.width / fractions.size
            val barW = slot * 0.56f
            val minH = 3.dp.toPx()
            fractions.forEachIndexed { i, f ->
                val h = if (f == 0f) minH else (size.height * f).coerceAtLeast(minH)
                val color = when {
                    i == highlightIndex -> PcColors.MintDeep
                    f == 0f -> PcColors.Line
                    else -> PcColors.Mint
                }
                drawRoundRect(color, topLeft = Offset(slot * i + (slot - barW) / 2f, size.height - h), size = Size(barW, h), cornerRadius = CornerRadius(barW / 3f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}
