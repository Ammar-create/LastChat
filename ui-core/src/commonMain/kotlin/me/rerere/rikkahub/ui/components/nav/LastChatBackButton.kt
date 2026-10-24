package me.rerere.rikkahub.ui.components.nav

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.motion.rememberControlPressValue

/** The production LastChat top-app-bar back control shared by Android and iOS. */
@Composable
fun LastChatBackButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onHaptic: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by rememberControlPressValue(isPressed, 0.92f, "back_button_scale")

    IconButton(
        onClick = {
            onHaptic()
            onClick()
        },
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource,
    ) {
        Icon(
            imageVector = RoundedArrowBackIcon,
            contentDescription = contentDescription,
        )
    }
}

private val RoundedArrowBackIcon by lazy {
    ImageVector.Builder(
        name = "AutoMirrored.Rounded.ArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString(
                "M20,11L7.83,11l5.59,-5.59c0.39,-0.39 0.39,-1.03 0,-1.42c-0.39,-0.39 -1.03,-0.39 -1.42,0l-7.29,7.29c-0.39,0.39 -0.39,1.02 0,1.41L12,19.98c0.39,0.39 1.03,0.39 1.42,0c0.39,-0.39 0.39,-1.03 0,-1.42L7.83,13L20,13c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1z",
            ).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }.build()
}
