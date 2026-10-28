package com.example.flowave.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Outline
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Continuous-curvature squircle (G2 superellipse) implementing [CornerBasedShape].
 * Renders authentic iOS-style smooth corners with continuous tangent and curvature
 * transitions instead of standard circular arc fillets.
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(radius: Dp) : this(
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
        CornerSize(radius),
    )

    constructor(percent: Int = 50) : this(
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
        CornerSize(percent),
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return Outline.Rectangle(Rect.Zero)

        val isLtr = layoutDirection == LayoutDirection.Ltr
        val tl = if (isLtr) topStart else topEnd
        val tr = if (isLtr) topEnd else topStart
        val br = if (isLtr) bottomEnd else bottomStart
        val bl = if (isLtr) bottomStart else bottomEnd

        if (tl <= 0f && tr <= 0f && br <= 0f && bl <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, w, h))
        }

        val path = createSquirclePath(w, h, tl, tr, br, bl)
        return Outline.Generic(path)
    }
}

/**
 * Generates an Apple G2 continuous curvature path for rectangles of width [w] and height [h].
 */
fun createSquirclePath(
    w: Float,
    h: Float,
    tlRadius: Float,
    trRadius: Float,
    brRadius: Float,
    blRadius: Float,
): Path {
    val path = Path()
    val maxRadius = minOf(w, h) / 2f
    val tl = tlRadius.coerceIn(0f, maxRadius)
    val tr = trRadius.coerceIn(0f, maxRadius)
    val br = brRadius.coerceIn(0f, maxRadius)
    val bl = blRadius.coerceIn(0f, maxRadius)

    val k = 1.528665f
    var lTl = tl * k
    var lTr = tr * k
    var lBr = br * k
    var lBl = bl * k

    val maxWTop = lTl + lTr
    if (maxWTop > w && maxWTop > 0f) {
        val scale = w / maxWTop
        lTl *= scale
        lTr *= scale
    }
    val maxWBottom = lBl + lBr
    if (maxWBottom > w && maxWBottom > 0f) {
        val scale = w / maxWBottom
        lBl *= scale
        lBr *= scale
    }
    val maxHLeft = lTl + lBl
    if (maxHLeft > h && maxHLeft > 0f) {
        val scale = h / maxHLeft
        lTl *= scale
        lBl *= scale
    }
    val maxHRight = lTr + lBr
    if (maxHRight > h && maxHRight > 0f) {
        val scale = h / maxHRight
        lTr *= scale
        lBr *= scale
    }

    path.moveTo(lTl, 0f)
    path.lineTo(w - lTr, 0f)
    if (lTr > 0.001f) {
        path.cubicTo(
            w - lTr * (1f - 0.712053f), 0f,
            w - lTr * (1f - 0.566789f), lTr * 0.030183f,
            w - lTr * (1f - 0.455953f), lTr * 0.087377f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.345118f), lTr * 0.144571f,
            w - lTr * (1f - 0.242846f), lTr * 0.242846f,
            w - lTr * (1f - 0.144571f), lTr * 0.345118f,
        )
        path.cubicTo(
            w - lTr * (1f - 0.087377f), lTr * 0.455953f,
            w - lTr * 0.030183f, lTr * (1f - 0.566789f),
            w, lTr * (1f - 0.712053f),
        )
        path.lineTo(w, lTr)
    } else {
        path.lineTo(w, 0f)
        path.lineTo(w, lTr)
    }

    path.lineTo(w, h - lBr)
    if (lBr > 0.001f) {
        path.cubicTo(
            w, h - lBr * (1f - 0.712053f),
            w - lBr * 0.030183f, h - lBr * (1f - 0.566789f),
            w - lBr * 0.087377f, h - lBr * (1f - 0.455953f),
        )
        path.cubicTo(
            w - lBr * 0.144571f, h - lBr * (1f - 0.345118f),
            w - lBr * 0.242846f, h - lBr * (1f - 0.242846f),
            w - lBr * 0.345118f, h - lBr * (1f - 0.144571f),
        )
        path.cubicTo(
            w - lBr * 0.455953f, h - lBr * (1f - 0.087377f),
            w - lBr * (1f - 0.566789f), h - lBr * 0.030183f,
            w - lBr * (1f - 0.712053f), h,
        )
        path.lineTo(w - lBr, h)
    } else {
        path.lineTo(w, h)
    }

    path.lineTo(lBl, h)
    if (lBl > 0.001f) {
        path.cubicTo(
            lBl * (1f - 0.712053f), h,
            lBl * (1f - 0.566789f), h - lBl * 0.030183f,
            lBl * (1f - 0.455953f), h - lBl * 0.087377f,
        )
        path.cubicTo(
            lBl * (1f - 0.345118f), h - lBl * 0.144571f,
            lBl * (1f - 0.242846f), h - lBl * 0.242846f,
            lBl * (1f - 0.144571f), h - lBl * 0.345118f,
        )
        path.cubicTo(
            lBl * (1f - 0.087377f), h - lBl * 0.455953f,
            lBl * 0.030183f, h - lBl * (1f - 0.566789f),
            0f, h - lBl * (1f - 0.712053f),
        )
        path.lineTo(0f, h - lBl)
    } else {
        path.lineTo(0f, h)
        path.lineTo(0f, h - lBl)
    }

    path.lineTo(0f, lTl)
    if (lTl > 0.001f) {
        path.cubicTo(
            0f, lTl * (1f - 0.712053f),
            lTl * 0.030183f, lTl * (1f - 0.566789f),
            lTl * 0.087377f, lTl * (1f - 0.455953f),
        )
        path.cubicTo(
            lTl * 0.144571f, lTl * (1f - 0.345118f),
            lTl * 0.242846f, lTl * (1f - 0.242846f),
            lTl * 0.345118f, lTl * (1f - 0.144571f),
        )
        path.cubicTo(
            lTl * 0.455953f, lTl * (1f - 0.087377f),
            lTl * (1f - 0.566789f), lTl * 0.030183f,
            lTl * (1f - 0.712053f), 0f,
        )
        path.lineTo(lTl, 0f)
    } else {
        path.lineTo(0f, 0f)
        path.lineTo(lTl, 0f)
    }
    path.close()
    return path
}
