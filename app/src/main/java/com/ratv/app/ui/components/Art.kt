package com.ratv.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.ratv.app.ui.theme.RColors

private class Motif(
    val top: Color,
    val bottom: Color,
    val c1: Color,
    val c2: Color,
    val c3: Color,
    val kind: Int
)

private val motifs = listOf(
    Motif(Color(0xFF4A2412), Color(0xFF1C0E08), Color(0xFFF2A23A), Color(0xFF5E2D14), Color(0xFF2A1309), 0),
    Motif(Color(0xFF13213F), Color(0xFF070B16), Color(0xFFEDE6D6), Color(0xFF0E1526), Color(0xFFC9D3EA), 1),
    Motif(Color(0xFF45143D), Color(0xFF12060F), Color(0xFFF3B9DC), Color(0x73F3B9DC), Color(0xFFF3B9DC), 2),
    Motif(Color(0xFF3B4352), Color(0xFF12151B), Color(0xFF1A1E26), Color(0xFF242A35), Color(0x29D6DCE8), 3),
    Motif(Color(0xFF33402F), Color(0xFF0E120D), Color(0xFF2A3526), Color(0xFF4C5D45), Color(0xFFE9E2C8), 4),
    Motif(Color(0xFF0F4450), Color(0xFF061A20), Color(0xBFA0E6EB), Color(0x66A0E6EB), Color(0xFF082228), 5),
    Motif(Color(0xFF2B1B3F), Color(0xFF120B1A), Color(0xFFF7D9A0), Color(0xFF3A2414), Color(0xFFF7D9A0), 1),
    Motif(Color(0xFF1D2738), Color(0xFF07090D), Color(0xFF5B6578), Color(0xFF0F131A), Color(0xFFFFE3A8), 6)
)

fun artSeed(text: String): Int = text.hashCode() and 0x7fffffff

/** Generated poster art used when a provider has no image (desert night motifs). */
@Composable
fun PosterArt(seed: Int, modifier: Modifier = Modifier) {
    val motif = motifs[seed % motifs.size]
    Canvas(modifier) { drawMotif(motif) }
}

private fun DrawScope.drawMotif(m: Motif) {
    val w = size.width
    val h = size.height
    drawRect(Brush.verticalGradient(listOf(m.top, m.bottom)))
    when (m.kind) {
        0 -> {
            val d = w * 0.6f
            drawCircle(m.c1, radius = d / 2f, center = Offset(w / 2f, h * 0.36f + d / 2f))
            drawOval(m.c2, topLeft = Offset(-0.35f * w, 0.64f * h), size = Size(1.5f * w, 0.5f * h))
            drawOval(m.c3, topLeft = Offset(0.12f * w, 0.75f * h), size = Size(1.3f * w, 0.5f * h))
        }
        1 -> {
            val r = w * 0.18f
            val center = Offset(w * 0.68f, h * 0.13f + r)
            val moon = Path().apply { addOval(Rect(center, r)) }
            val cut = Path().apply { addOval(Rect(Offset(center.x - r * 0.42f, center.y + r * 0.22f), r)) }
            drawPath(Path.combine(PathOperation.Difference, moon, cut), m.c1)
            val stars = listOf(0.18f to 0.18f, 0.32f to 0.40f, 0.82f to 0.46f, 0.12f to 0.55f, 0.64f to 0.60f)
            stars.forEachIndexed { i, (sx, sy) ->
                drawCircle(m.c3, radius = if (i % 2 == 0) 1.6f else 1.1f, center = Offset(sx * w, sy * h))
            }
            drawOval(m.c2, topLeft = Offset(-0.25f * w, 0.74f * h), size = Size(1.5f * w, 0.5f * h))
        }
        2 -> {
            val c = Offset(w / 2f, h * 0.42f)
            val strokeWidth = (w * 0.012f).coerceAtLeast(1.2f)
            drawCircle(m.c2, radius = w * 0.43f, center = c, style = Stroke(width = strokeWidth))
            drawCircle(m.c2, radius = w * 0.29f, center = c, style = Stroke(width = strokeWidth))
            drawCircle(m.c1, radius = w * 0.15f, center = c)
            drawCircle(m.c1, radius = w * 0.035f, center = Offset(w * 0.8f, h * 0.24f))
        }
        3 -> {
            val baseY = h
            val heights = listOf(0.33f, 0.56f, 0.42f, 0.64f, 0.37f)
            val weights = listOf(1f, 1.3f, 1f, 0.8f, 1.1f)
            val pad = w * 0.06f
            val gap = w * 0.04f
            val totalWeight = weights.sum()
            val usable = w - pad * 2 - gap * (weights.size - 1)
            var x = pad
            heights.forEachIndexed { i, hh ->
                val bw = usable * weights[i] / totalWeight
                drawRect(if (i % 2 == 0) m.c2 else m.c1, topLeft = Offset(x, baseY - hh * h), size = Size(bw, hh * h))
                x += bw + gap
            }
            drawRect(m.c3, topLeft = Offset(0f, h * 0.54f), size = Size(w, h * 0.16f))
            val windows = listOf(0.34f to 0.52f, 0.63f to 0.46f, 0.70f to 0.62f)
            windows.forEach { (wx, wy) ->
                drawRect(Color(0xFFFFC56B), topLeft = Offset(wx * w, wy * h), size = Size(w * 0.025f, h * 0.022f))
            }
        }
        4 -> {
            drawCircle(m.c3, radius = w * 0.07f, center = Offset(w * 0.69f, h * 0.16f + w * 0.07f))
            val back = Path().apply {
                moveTo(-0.1f * w, h); lineTo(0.36f * w, 0.36f * h); lineTo(0.8f * w, h); close()
            }
            drawPath(back, m.c2)
            val front = Path().apply {
                moveTo(0.28f * w, h); lineTo(0.7f * w, 0.46f * h); lineTo(1.12f * w, h); close()
            }
            drawPath(front, m.c1)
        }
        5 -> {
            drawCircle(m.c1, radius = w * 0.09f, center = Offset(w * 0.69f, h * 0.34f + w * 0.09f))
            val sw = (w * 0.015f).coerceAtLeast(1.5f)
            drawOval(m.c1, topLeft = Offset(-0.3f * w, 0.52f * h), size = Size(1.6f * w, 0.7f * h), style = Stroke(width = sw))
            drawOval(m.c2, topLeft = Offset(-0.45f * w, 0.62f * h), size = Size(1.7f * w, 0.7f * h), style = Stroke(width = sw))
            drawOval(m.c3, topLeft = Offset(-0.2f * w, 0.72f * h), size = Size(1.5f * w, 0.7f * h))
        }
        else -> {
            drawRect(m.c2, topLeft = Offset(0f, h * 0.56f), size = Size(w, h * 0.44f))
            val left = Path().apply {
                moveTo(0.485f * w, 0.56f * h); lineTo(0.495f * w, 0.56f * h); lineTo(0.30f * w, h); lineTo(0.24f * w, h); close()
            }
            val right = Path().apply {
                moveTo(0.505f * w, 0.56f * h); lineTo(0.515f * w, 0.56f * h); lineTo(0.76f * w, h); lineTo(0.70f * w, h); close()
            }
            drawPath(left, m.c1)
            drawPath(right, m.c1)
            drawCircle(m.c3.copy(alpha = 0.35f), radius = w * 0.12f, center = Offset(w / 2f, h * 0.52f))
            drawCircle(m.c3, radius = w * 0.05f, center = Offset(w / 2f, h * 0.52f))
        }
    }
}

/** Concentric "sun" rings from the brand, used behind heroes and the splash. */
@Composable
fun RingsArt(modifier: Modifier = Modifier, color: Color = RColors.Surface3) {
    Canvas(modifier) {
        val c = Offset(size.width * 0.18f, size.height * 0.45f)
        val base = size.minDimension
        drawCircle(color.copy(alpha = 0.55f), radius = base * 0.42f, center = c, style = Stroke(width = 2f))
        drawCircle(color.copy(alpha = 0.35f), radius = base * 0.68f, center = c, style = Stroke(width = 2f))
        drawCircle(color.copy(alpha = 0.2f), radius = base * 0.96f, center = c, style = Stroke(width = 2f))
        drawCircle(RColors.Saffron, radius = 4f, center = Offset(c.x + base * 0.30f, c.y - base * 0.30f))
    }
}
