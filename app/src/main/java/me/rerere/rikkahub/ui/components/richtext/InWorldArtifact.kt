package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.chat.LocalMessageBubbleShape
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalChatAnimationsEnabled
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.LocalOpticalFrame
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.writeClipboardText

private val artifactHeightCache = LruCache<String, Int>(100)

private class ArtifactInterface(
    val onHeightChanged: (Int) -> Unit
) {
    @JavascriptInterface
    fun onHeightChanged(height: Int) {
        onHeightChanged(height)
    }
}

/**
 * Animated glowing holographic orb rendered while an in-world visual artifact is generating.
 * Replaces ugly monospace code streaming with an immersive, living cosmic globe.
 */
@Composable
fun ArtifactOrbGenerating(
    modifier: Modifier = Modifier,
    statusText: String = "Forging in-world visual..."
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ArtifactOrbTransition")

    // Harmonic breathing pulse
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbScale"
    )

    // Halo pulse alpha
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HaloAlpha"
    )

    // Outer orbit rotation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "OrbRotation"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Celestial Holographic Sphere
            Box(
                modifier = Modifier.size(90.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(86.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2f * 0.72f

                    // 1. Soft atmospheric outer halo
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8338EC).copy(alpha = 0.45f * haloAlpha),
                                Color(0xFFFF007F).copy(alpha = 0.20f * haloAlpha),
                                Color.Transparent
                            ),
                            center = center,
                            radius = baseRadius * 1.6f * scale
                        ),
                        radius = baseRadius * 1.6f * scale,
                        center = center
                    )

                    // 2. Rotating orbital energy ring
                    rotate(rotation, pivot = center) {
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    Color(0xFF3A86FF).copy(alpha = 0.7f),
                                    Color(0xFFFF006E).copy(alpha = 0.8f),
                                    Color(0xFFFFBE0B).copy(alpha = 0.4f),
                                    Color(0xFF8338EC).copy(alpha = 0.7f),
                                    Color(0xFF3A86FF).copy(alpha = 0.7f)
                                ),
                                center = center
                            ),
                            radius = baseRadius * 1.15f * scale,
                            center = center,
                            style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // 3. Dense glowing core orb
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFFFFF).copy(alpha = 0.95f),
                                Color(0xFFFF007F).copy(alpha = 0.85f),
                                Color(0xFF7209B7).copy(alpha = 0.90f),
                                Color(0xFF0F0C29).copy(alpha = 0.95f)
                            ),
                            center = Offset(center.x - baseRadius * 0.25f, center.y - baseRadius * 0.25f),
                            radius = baseRadius * scale
                        ),
                        radius = baseRadius * scale,
                        center = center
                    )

                    // 4. Glass specular highlight
                    drawCircle(
                        color = Color.White.copy(alpha = 0.45f),
                        radius = baseRadius * 0.22f * scale,
                        center = Offset(center.x - baseRadius * 0.35f, center.y - baseRadius * 0.35f)
                    )
                }
            }

            // Materializing Status Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFFFF007F).copy(alpha = haloAlpha),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Render an in-world visual artifact (notes, letters, phone screens, receipts)
 * inside chat flow with a smooth slow-motion phasing-out transition from the orb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InWorldArtifact(
    code: String,
    modifier: Modifier = Modifier,
) {
    val darkMode = LocalDarkMode.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val chatAnimationsEnabled = LocalChatAnimationsEnabled.current
    val scope = rememberCoroutineScope()

    var showSource by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val sanitizeResult: ArtifactSanitizeResult = remember(code, darkMode) {
        ArtifactSanitizer.sanitize(code, darkMode)
    }

    when (sanitizeResult) {
        is ArtifactSanitizeResult.Rejected -> {
            HighlightCodeBlock(
                code = code,
                language = "artifact",
                modifier = modifier,
                completeCodeBlock = true
            )
        }
        is ArtifactSanitizeResult.Success -> {
            // Sizing: initial estimate from code length so it never starts as a truncated 120dp slit
            val initialEstimate = remember(code) {
                artifactHeightCache.get(code) ?: (code.length * 0.72f).toInt().coerceIn(260, 850)
            }
            var contentHeightDp by remember(code) {
                mutableIntStateOf(initialEstimate)
            }

            val jsInterface = remember(code) {
                ArtifactInterface { measuredHeight ->
                    if (measuredHeight > 80) {
                        // In Android WebView with initial-scale=1, CSS pixels map directly to dp
                        contentHeightDp = measuredHeight
                        artifactHeightCache.put(code, measuredHeight)
                    }
                }
            }

            val webViewState = rememberWebViewState(
                data = sanitizeResult.documentHtml,
                baseUrl = "http://localhost/",
                mimeType = "text/html",
                encoding = "utf-8",
                interfaces = mapOf("AndroidInterface" to jsInterface),
                settings = {
                    builtInZoomControls = false
                    displayZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false
                }
            )

            AnimatedContent(
                targetState = showSource,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(animationSpec = tween(400, easing = FastOutSlowInEasing))
                },
                label = "ArtifactSourceTransition"
            ) { isSourceMode ->
                if (isSourceMode) {
                    HighlightCodeBlock(
                        code = code,
                        language = "artifact",
                        modifier = modifier,
                        completeCodeBlock = true
                    )
                } else {
                    val frame = LocalOpticalFrame.current
                    val bubbleShape = LocalMessageBubbleShape.current
                    val blockShape = bubbleShape ?: if (frame.nested) frame.innerShape else AppShapes.InputField

                    Surface(
                        modifier = modifier
                            .combinedClickable(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    showActionSheet = true
                                },
                                onLongClick = {
                                    haptics.perform(HapticPattern.Thud)
                                    showActionSheet = true
                                }
                            ),
                        shape = blockShape,
                        color = Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .then(
                                    if (chatAnimationsEnabled) {
                                        Modifier.animateContentSize(
                                            animationSpec = tween(
                                                durationMillis = 350,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            WebView(
                                state = webViewState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(contentHeightDp.dp),
                                onCreated = { webView ->
                                    webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webView.isVerticalScrollBarEnabled = false
                                    webView.isHorizontalScrollBarEnabled = false
                                },
                                onUpdated = { webView ->
                                    webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                }
                            )
                        }
                    }
                }
            }

            // Bottom action sheet on click/long-press
            if (showActionSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showActionSheet = false },
                    sheetState = sheetState,
                    shape = AppShapes.BottomSheet
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.artifact_in_world_visual),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )

                        // Copy HTML action
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.artifact_copy_html)) },
                            leadingContent = {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                            },
                            modifier = Modifier.combinedClickable {
                                haptics.perform(HapticPattern.Pop)
                                context.writeClipboardText(code)
                                toaster.show(
                                    context.getString(R.string.artifact_copy_success),
                                    type = ToastType.Success
                                )
                                scope.launch {
                                    sheetState.hide()
                                    showActionSheet = false
                                }
                            }
                        )

                        // View Source / Visual toggle
                        ListItem(
                            headlineContent = {
                                Text(
                                    stringResource(
                                        if (showSource) R.string.artifact_view_rendered
                                        else R.string.artifact_view_source
                                    )
                                )
                            },
                            leadingContent = {
                                Icon(
                                    if (showSource) Icons.Rounded.Visibility else Icons.Rounded.Code,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.combinedClickable {
                                haptics.perform(HapticPattern.Pop)
                                showSource = !showSource
                                scope.launch {
                                    sheetState.hide()
                                    showActionSheet = false
                                }
                            }
                        )

                        // Save as Image action
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.artifact_save_image)) },
                            leadingContent = {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                            },
                            modifier = Modifier.combinedClickable {
                                haptics.perform(HapticPattern.Pop)
                                val webView = webViewState.webView
                                val currentActivity = activity
                                if (webView != null && currentActivity != null) {
                                    runCatching {
                                        val width = webView.width
                                        val height = webView.height
                                        check(width > 0 && height > 0) { "WebView dimensions zero" }
                                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                        val canvas = Canvas(bitmap)
                                        webView.draw(canvas)
                                        context.exportImage(
                                            currentActivity,
                                            bitmap,
                                            "artifact_${System.currentTimeMillis()}.png"
                                        )
                                        toaster.show(
                                            context.getString(R.string.artifact_save_success),
                                            type = ToastType.Success
                                        )
                                    }.onFailure {
                                        toaster.show(
                                            context.getString(R.string.artifact_save_failed),
                                            type = ToastType.Error
                                        )
                                    }
                                }
                                scope.launch {
                                    sheetState.hide()
                                    showActionSheet = false
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
