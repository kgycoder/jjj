package com.agent.arena.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Kyant0/AndroidLiquidGlass 카탈로그의 LiquidButton 과 동일한 구성:
 * drawBackdrop + (vibrancy → blur → lens) + 캡슐 shape + 눌림 스케일 애니메이션 + onDrawSurface 틴트.
 */
fun Modifier.glass(
    backdrop: Backdrop,
    shape: Shape,
    surface: Color,
    scale: () -> Float = { 1f },
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        // blur 는 Android 12+, lens(AGSL) 는 Android 13+ 에서만 적용 (그 미만은 반투명 표면만)
        if (Build.VERSION.SDK_INT >= 31) {
            vibrancy()
            blur(3f.dp.toPx())
        }
        if (Build.VERSION.SDK_INT >= 33) {
            lens(14f.dp.toPx(), 28f.dp.toPx())
        }
    },
    layerBlock = {
        val s = scale()
        scaleX = s
        scaleY = s
    },
    onDrawSurface = { drawRect(surface) },
)

val CapsuleShape = RoundedCornerShape(percent = 50)

/** 눌림에 반응하는 Liquid Glass 컨테이너(버튼). */
@Composable
fun GlassBox(
    backdrop: Backdrop,
    surface: Color,
    modifier: Modifier = Modifier,
    shape: Shape = CapsuleShape,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 1.10f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "glassPress",
    )
    Box(
        modifier = modifier
            .glass(backdrop, shape, surface) { scale }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ------------------------------------------------------------------ 아이콘 (Canvas)

@Composable
fun PlusIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    drawLine(color, Offset(size.width * 0.15f, size.height / 2), Offset(size.width * 0.85f, size.height / 2), sw, StrokeCap.Round)
    drawLine(color, Offset(size.width / 2, size.height * 0.15f), Offset(size.width / 2, size.height * 0.85f), sw, StrokeCap.Round)
}

@Composable
fun SlidersIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.1f, h * 0.32f), Offset(w * 0.9f, h * 0.32f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.1f, h * 0.68f), Offset(w * 0.9f, h * 0.68f), sw, StrokeCap.Round)
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.35f, h * 0.32f))
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.68f, h * 0.68f))
}

@Composable
fun ArrowUpIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.2f.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.14f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.84f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
}

@Composable
fun StopIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    drawRoundRect(color, topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
        size = Size(size.width * 0.64f, size.height * 0.64f), cornerRadius = CornerRadius(3.dp.toPx()))
}
