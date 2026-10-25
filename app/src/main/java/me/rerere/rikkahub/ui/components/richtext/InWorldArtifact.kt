package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import androidx.activity.compose.LocalActivity
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
 * Render an in-world visual artifact (notes, letters, phone screens, receipts)
 * inside chat flow as a self-contained, styled object without developer chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InWorldArtifact(
    code: String,
    modifier: Modifier = Modifier,
) {
    val darkMode = LocalDarkMode.current
    val density = LocalDensity.current
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
            // Fallback cleanly to standard syntax highlighted code block if rejected
            HighlightCodeBlock(
                code = code,
                language = "html",
                modifier = modifier,
                completeCodeBlock = true
            )
        }
        is ArtifactSanitizeResult.Success -> {
            var contentHeight by remember(code) {
                mutableIntStateOf(artifactHeightCache.get(code) ?: 180)
            }
            val heightDp = with(density) {
                (contentHeight / density.density).dp.coerceIn(80.dp, 650.dp)
            }

            val jsInterface = remember(code) {
                ArtifactInterface { measuredHeight ->
                    if (measuredHeight > 0) {
                        val px = (measuredHeight * density.density).toInt()
                        contentHeight = px
                        artifactHeightCache.put(code, px)
                    }
                }
            }

            val webViewState = rememberWebViewState(
                data = sanitizeResult.documentHtml,
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

            if (showSource) {
                HighlightCodeBlock(
                    code = code,
                    language = "html",
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
                                            durationMillis = 180,
                                            easing = LinearOutSlowInEasing
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
                                .height(heightDp),
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
