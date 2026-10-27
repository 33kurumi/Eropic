package io.github.kurumi33.eropic.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import kotlin.math.abs

/** 缩放下限：1 倍即原图铺满屏幕。 */
private const val MIN_SCALE = 1f

/** 缩放上限。 */
private const val MAX_SCALE = 5f

/** 超过该倍率就视为"已放大"，此时禁用 Pager 的左右翻页，让单指拖动用于平移画面。 */
private const val ZOOMED_THRESHOLD = 1.02f

/** 双击放大到的倍率；再双击回到 1 倍。 */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * 可缩放的图片：双指缩放（1x~5x）、放大后单指拖动平移、双击在 1x / 2.5x 之间切换。
 *
 * 手势处理的要点：
 * - 只有"双指"或"已放大后的单指拖动"才会消费触摸事件，未放大时的单指左右滑动继续交给
 *   外层 Pager 翻页；
 * - 缩放以双指中点为锚点，避免画面从手指下方"跳走"；
 * - 缩回 1 倍时把位移复位，避免出现黑边。
 *
 * @param model Coil 的图片来源，通常传 MediaUris.of(id)。
 * @param contentDescription 无障碍描述，一般用文件名。
 * @param onZoomChanged 放大状态变化回调，用于让外层决定是否允许 Pager 翻页。
 * @param onTap 单击回调，查看器用它切换标题栏等 UI 的显隐。
 * @param resetKey 变化时把缩放复位，查看器传当前图片 id。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onZoomChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
    resetKey: Any? = null,
) {
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val latestOnTap by rememberUpdatedState(onTap)

    // 只在跨过阈值时触发重组，避免缩放过程中每一帧都重组。
    val zoomed by remember { derivedStateOf { scale > ZOOMED_THRESHOLD } }
    LaunchedEffect(zoomed) { latestOnZoomChanged(zoomed) }

    LaunchedEffect(resetKey) {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pastTouchSlop = false
                    val touchSlop = viewConfiguration.touchSlop
                    do {
                        val event = awaitPointerEvent()
                        // 事件已被其它手势（例如 Pager 的拖动）消费时不再处理。
                        if (event.changes.any { it.isConsumed }) break

                        // 有手指抬起的那一帧不处理缩放/平移，避免参考点数量变化导致画面跳动。
                        val anyUp = event.changes.any { !it.pressed }
                        val zoomChange = if (anyUp) 1f else event.calculateZoom()
                        val panChange = if (anyUp) Offset.Zero else event.calculatePan()
                        val pressedCount = event.changes.count { it.pressed }

                        if (!pastTouchSlop) {
                            val zoomMotion = abs(1f - zoomChange) * size.width
                            val panMotion = panChange.getDistance()
                            val pinchStart = pressedCount >= 2 &&
                                (zoomMotion > touchSlop || panMotion > touchSlop)
                            val dragWhileZoomed = scale > ZOOMED_THRESHOLD && panMotion > touchSlop
                            if (pinchStart || dragWhileZoomed) pastTouchSlop = true
                        }

                        if (pastTouchSlop) {
                            val oldScale = scale
                            val newScale = (oldScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                            var newOffset = offset
                            if (newScale != oldScale) {
                                // 以双指中点为锚点缩放：锚点下的图像位置保持不动。
                                val factor = newScale / oldScale
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid.x.isFinite() && centroid.y.isFinite()) {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val anchor = centroid - center
                                    newOffset = (newOffset - anchor) * factor + anchor
                                }
                            }
                            newOffset += panChange
                            scale = newScale
                            offset = if (newScale <= MIN_SCALE) {
                                Offset.Zero
                            } else {
                                clampOffset(newOffset, newScale, size)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > ZOOMED_THRESHOLD) {
                            // 已放大：双击还原。
                            scale = MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            // 未放大：以双击位置为锚点放大到 2.5 倍。
                            val oldScale = scale
                            val factor = DOUBLE_TAP_SCALE / oldScale
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val anchor = tap - center
                            val newOffset = (offset - anchor) * factor + anchor
                            scale = DOUBLE_TAP_SCALE
                            offset = clampOffset(newOffset, DOUBLE_TAP_SCALE, size)
                        }
                    },
                    onTap = { latestOnTap() },
                )
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** 把平移限制在缩放后多出来的画面范围内，避免拖出空白。 */
private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    if (scale <= MIN_SCALE) return Offset.Zero
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}
