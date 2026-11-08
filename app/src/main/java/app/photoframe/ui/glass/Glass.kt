package app.photoframe.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The thing behind the glass. The slideshow records itself into [layer]; glass surfaces
 * re-draw that layer offset to their own position, then blur + refract it.
 */
class Backdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/** iOS-26-style "liquid glass": lens refraction at the rim, chromatic fringe, vibrancy, specular edge. */
private const val GLASS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float saturation;
uniform float brighten;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float d = sdRoundRect(p, c, radius);
    float k = 1.0 - clamp(-d / bezel, 0.0, 1.0);      // 1 at the rim → 0 inside
    float2 n = float2(
        sdRoundRect(p + float2(1.0, 0.0), c, radius) - sdRoundRect(p - float2(1.0, 0.0), c, radius),
        sdRoundRect(p + float2(0.0, 1.0), c, radius) - sdRoundRect(p - float2(0.0, 1.0), c, radius));
    n = n / (length(n) + 1e-4);

    // Convex lens profile: displacement grows steeply toward the rim.
    float disp = strength * k * k * k;
    float2 off = -n * disp;

    half4 col;
    col.r = content.eval(coord + off * 1.08).r;
    col.g = content.eval(coord + off).g;
    col.b = content.eval(coord + off * 0.92).b;
    col.a = 1.0;

    half l = dot(col.rgb, half3(0.2126, 0.7152, 0.0722));
    col.rgb = mix(half3(l), col.rgb, half(saturation));
    col.rgb = col.rgb + half3(brighten);

    // Specular rim lit from the top-left, soft shade bottom-right.
    float rim = pow(k, 4.0);
    float lit = max(dot(n, float2(-0.6, -0.8)), 0.0);
    float shade = max(dot(n, float2(0.6, 0.8)), 0.0);
    col.rgb += half3(rim * (0.10 + 0.55 * lit));
    col.rgb -= half3(rim * 0.10 * shade);
    return half4(clamp(col.rgb, 0.0, 1.0), 1.0);
}
"""

private class GlassEffectCache {
    var key: Any? = null
    var effect: androidx.compose.ui.graphics.RenderEffect? = null
}

private fun buildEffect(size: Size, radiusPx: Float, blurPx: Float, refraction: Boolean): androidx.compose.ui.graphics.RenderEffect {
    val blur = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
    if (!refraction || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || size.minDimension <= 0f) {
        return blur.asComposeRenderEffect()
    }
    val shader = RuntimeShader(GLASS_AGSL).apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("radius", radiusPx.coerceAtMost(size.minDimension / 2f))
        setFloatUniform("bezel", (size.minDimension * 0.28f).coerceIn(8f, 64f))
        setFloatUniform("strength", (size.minDimension * 0.22f).coerceIn(6f, 48f))
        setFloatUniform("saturation", 1.45f)
        setFloatUniform("brighten", 0.0f)
    }
    val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
    return RenderEffect.createChainEffect(lens, blur).asComposeRenderEffect()
}

/**
 * A liquid-glass panel. Content is drawn crisp on top of the refracted backdrop.
 *
 * @param tint darkening/brightening wash for legibility on bright photos.
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    shape: Shape = RoundedCornerShape(cornerRadius),
    blur: Dp = 18.dp,
    tint: Color = Color.Black.copy(alpha = 0.18f),
    refraction: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { cornerRadius.toPx() }
    val blurPx = with(density) { blur.toPx() }
    var pos by remember { mutableStateOf(Offset.Zero) }
    val cache = remember { GlassEffectCache() }

    Box(modifier.onGloballyPositioned { pos = it.positionInRoot() }) {
        // 1. refracted, blurred copy of the backdrop
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    clip = true
                    this.shape = shape
                    val key = listOf(size, radiusPx, blurPx, refraction)
                    if (cache.key != key) {
                        cache.key = key
                        cache.effect = buildEffect(size, radiusPx, blurPx, refraction)
                    }
                    renderEffect = cache.effect
                }
                .drawBehind {
                    val o = backdrop.origin - pos
                    translate(o.x, o.y) { drawLayer(backdrop.layer) }
                    drawRect(tint)
                }
        )
        // 2. hairline rim (light catches the top-left edge)
        Box(
            Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.55f),
                        0.35f to Color.White.copy(alpha = 0.08f),
                        0.7f to Color.White.copy(alpha = 0.05f),
                        1f to Color.White.copy(alpha = 0.30f),
                    ),
                    shape = shape,
                )
        )
        content()
    }
}
