package me.rerere.rikkahub.ui.components.avatar.animated

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * Soft-3D mark — desktop Grok Bot 0.68.1 body (flat fill of the projected solid) with
 * eyes seated on the body surface in BOTH eye modes:
 *  - Grok: desktop capsule eyes (sphere SS / mesh `sx` seats), morphing between expressions.
 *  - Generical: the reference SVG eyes, resampled and morphed, seated with the same
 *    surface math so they wrap and foreshorten with the solid.
 * Motion comes from [AvatarMotionState] (crossfaded clips, gaze, blinks, alive layer).
 * Gaze rests forward (no Grok up-right brand stare).
 */
@Composable
fun AnimatedMarkAvatar(
    shapeId: String,
    eyeType: String,
    colorHex: String,
    isLoading: Boolean,
    isTyping: Boolean = false,
    colorPreset: String? = null,
    /** Null = mode default eye color. */
    eyeColorHex: String? = null,
    calmPreview: Boolean = false,
    /**
     * False = static (older chat messages): eases to the rest pose and stops; flipping
     * back to true eases into motion again. Ignored for calm previews / harness frames.
     */
    animate: Boolean = true,
    modifier: Modifier = Modifier,
    /** Render-harness hook: freeze the live path at this clip pose. Null in the app. */
    debugPose: ClipSample? = null,
    /** Render-harness hook: full frame override (pose, gaze, lids, expression morph). */
    debugFrame: MotionFrame? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AvatarPackData.ensureLoaded(context) }
    runCatching { AvatarPackData.ensureLoaded(context) }

    val frozen = calmPreview || debugPose != null || debugFrame != null
    val live = animate && !frozen
    val motionState = rememberAvatarMotionState(
        isLoading = if (frozen) false else isLoading,
        isTyping = if (frozen) false else isTyping,
        enabled = !frozen,
        animate = live,
    )
    val isGrok = eyeType.equals("grok", ignoreCase = true)
    // Swatch colour == rendered colour (see MarkColors).
    val bodyColor = MarkColors.bodyColor(colorHex, colorPreset, eyeType)
    val eyeColor = MarkColors.eyeColor(eyeType, eyeColorHex, colorHex, colorPreset)
    val eyeFade = MarkColors.genericalEyeFade(bodyColor, eyeColor)

    // Shape swap (editor) gets a soft settle; first appearance never pops in size.
    val morphAnim = remember { Animatable(1f) }
    val lastShape = remember { mutableStateOf(shapeId) }
    LaunchedEffect(shapeId) {
        if (lastShape.value == shapeId || frozen) return@LaunchedEffect
        lastShape.value = shapeId
        morphAnim.snapTo(0.94f)
        morphAnim.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }

    // --- shared stage: where am I, so neighbours can glance at me ---
    val stage = LocalAvatarStage.current
    val view = LocalView.current
    val stageId = remember { stage.newId() }
    val liveNow = rememberUpdatedState(live)
    if (!frozen) {
        motionState.stage = stage
        motionState.stageId = stageId
        DisposableEffect(stageId) {
            onDispose { stage.remove(stageId) }
        }
    }
    val signature = "$shapeId|${eyeType.lowercase()}"
    val lastBounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    fun register() {
        val b = lastBounds[0]
        if (!liveNow.value || b == null || b.width <= 0f || b.height <= 0f) {
            stage.remove(stageId)
            return
        }
        val root = view.rootView
        stage.update(
            id = stageId,
            windowKey = System.identityHashCode(root),
            center = b.center,
            radius = minOf(b.width, b.height) / 2f,
            signature = signature,
            windowW = root.width.toFloat(),
            windowH = root.height.toFloat(),
        )
    }
    // Static avatars are not on the stage (nobody glances at a statue); when one comes
    // alive again it re-registers right away, not on the next layout pass.
    if (!frozen) LaunchedEffect(live) { register() }

    var boxModifier = modifier
    if (!frozen) {
        boxModifier = boxModifier
            .onGloballyPositioned { coords ->
                lastBounds[0] = coords.boundsInWindow()
                register()
            }
            .pointerInput(Unit) {
                // Observe pokes without consuming, so parent click handlers still work.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    motionState.poke()
                }
            }
    }

    Box(modifier = boxModifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (!AvatarPackData.isLoaded()) return@Canvas
            val f = when {
                calmPreview -> CALM_FRAME
                debugFrame != null -> debugFrame
                debugPose != null -> frameFromClip(debugPose)
                else -> motionState.frame
            }
            val shape = MarkShapes.resolve(shapeId) ?: return@Canvas
            drawMark(
                shape = shape,
                shapeId = shapeId,
                isGrok = isGrok,
                f = f,
                calm = calmPreview,
                morph = if (frozen) 1f else morphAnim.value,
                body = bodyColor,
                eye = eyeColor,
                eyeFade = eyeFade,
            )
        }
    }
}

internal val CALM_FRAME = MotionFrame()

/** Harness: a raw clip pose as a frame (expression from the clip, eyes open). */
private fun frameFromClip(c: ClipSample): MotionFrame = MotionFrame(
    body = BodyPose(
        pitch = c.rotationDeg[0],
        yaw = c.rotationDeg[1],
        roll = c.rotationDeg[2],
        posY = c.position[1],
        squashX = c.squash[0],
        squashY = c.squash[1],
        scale = c.scale / MarkClipEngine.DEFAULT_ZOOM,
        lidOpen = c.lids,
    ),
    exprFrom = ExpressionDirector.normalize(c.expression),
    exprTo = ExpressionDirector.normalize(c.expression),
)

internal fun DrawScope.drawMark(
    shape: GrokShape,
    shapeId: String,
    isGrok: Boolean,
    f: MotionFrame,
    calm: Boolean,
    morph: Float,
    body: Color,
    eye: Color,
    eyeFade: Color,
) {
    val he = AvatarPackData.grok.headCenter
    val bodyRaw = AvatarPackData.parseSvgPath(shape.pathData)
    val bodyCentered = Path().apply { addPath(bodyRaw, Offset(-he, -he)) }
    val sizePx = size.minDimension * Soft3DMark.FILL * shape.scale.coerceAtLeast(0.95f)
    val bodyKind = shape.solid.kind

    // Head look (gaze) is an euler add-on on body + eyes (desktop setGaze → Ou).
    // Screen y (+down) → pitch (+down).
    val look = WhSphereEyes.ou(bodyKind, f.headLookX, -f.headLookY)
    val pitch = f.body.pitch + look.pitchDeg
    val yaw = f.body.yaw + look.yawDeg
    val roll = f.body.roll
    val clip = ClipSample(
        clipName = "live",
        time = 0f,
        duration = 1f,
        squashAnchor = 0f,
        rotationDeg = floatArrayOf(pitch, yaw, roll),
        position = floatArrayOf(0f, f.body.posY),
        squash = floatArrayOf(f.body.squashX, f.body.squashY),
        scale = MarkClipEngine.DEFAULT_ZOOM * f.body.scale,
        lids = 1f,
        expression = f.exprTo,
    )
    val yawR = Math.toRadians(yaw.toDouble()).toFloat()
    val pitchR = Math.toRadians(pitch.toDouble()).toFloat()
    val rollR = Math.toRadians(roll.toDouble()).toFloat()
    val projected = Soft3DMark.projectBody(bodyCentered, shape.solid, yawR, pitchR, rollR, he, shapeId = shape.id)

    Soft3DMark.drawBody(
        drawScope = this,
        bodyPath = bodyCentered,
        fill = body,
        solid = shape.solid,
        clip = clip,
        gazeX = 0f,
        gazeY = 0f,
        sizePx = sizePx,
        morphProgress = morph,
        markRadius = he,
        mainFillPath = projected,
        calmFaceOn = calm,
        allowXBob = false,
        shapeId = shape.id,
    )

    val lid = f.lid.coerceIn(0f, 1f)
    if (isGrok) {
        val lidForPair = if (bodyKind == "sphere") lid * f.eyeScale.coerceIn(0.5f, 1.2f) else lid
        val pair = WhSphereEyes.morphPair(f.exprFrom, f.exprTo, f.exprT, lidForPair)
        var (left, right) = WhEyeSeat.buildSeatedGrokEyes(
            pickerId = shapeId,
            solidKind = bodyKind,
            he = he,
            gazeX = 0f,
            gazeY = 0f,
            lid = lid,
            eyeGap = f.eyeGap * 0.92f,
            eyeScale = f.eyeScale,
            timeSec = f.timeSec,
            awake = if (calm) 0f else 1f,
            yawDeg = yaw,
            pitchDeg = pitch,
            rollDeg = roll,
            shape = shape,
            bodyPath = bodyCentered,
            expressionId = WhSphereEyes.grokNameFor(f.exprTo),
            eyesOverride = pair,
            leadX = f.leadX,
            leadY = f.leadY,
        )
        // Reject only empty / degenerate seating, never valid capsules.
        fun ok(p: Path): Boolean {
            if (p.isEmpty) return false
            val b = p.getBounds()
            return b.width >= he * 0.02f && b.height >= he * 0.005f && b.width <= he * 1.6f && b.height <= he * 1.6f
        }
        if (!(ok(left) && ok(right)) && bodyKind == "sphere") {
            val ss = WhSphereEyes.projectPair(WhSphereEyes.expressionPair("neutral", lidForPair), pitch, yaw, roll, he)
            left = ss.first; right = ss.second
        }
        if (lid >= 0.07f) {
            Soft3DMark.withBodyTransform(this, clip, sizePx, he, morph, false) {
                if (ok(left)) drawPath(left, eye)
                if (ok(right)) drawPath(right, eye)
            }
        }
        return
    }

    // Generical: morph → seat on the surface → eye-colour fill fading into the surface tint.
    val closure = (1f - lid).coerceIn(0f, 1f)
    val lo = GenericalFace.outline(0, f.exprFrom, f.exprTo, f.exprT, closure) ?: return
    val ro = GenericalFace.outline(1, f.exprFrom, f.exprTo, f.exprT, closure) ?: return
    val gap = WhEyeSeat.genericalGapScale(shapeId) * (f.eyeGap / 0.94f).coerceIn(0.92f, 1.06f)
    val fit = WhEyeSeat.genericalFitScale(shapeId)
    val (le, re) = GenericalSeat.seat(
        shape = shape,
        he = he,
        bodyPath = bodyCentered,
        left = lo,
        right = ro,
        gap = gap,
        fit = fit,
        leadX = f.leadX,
        leadY = f.leadY,
        pitchDeg = pitch,
        yawDeg = yaw,
        rollDeg = roll,
    )
    val drawEyes: DrawScope.() -> Unit = {
        le?.let { GenericalSeat.draw(this, it, eye, eyeFade, he) }
        re?.let { GenericalSeat.draw(this, it, eye, eyeFade, he) }
    }
    if (calm) {
        Soft3DMark.withBodyTransform(this, clip, sizePx, he, morph, false, drawEyes)
    } else {
        // Clip to the projected silhouette so an eye rolling over the edge wraps away.
        Soft3DMark.withBodyClip(this, clip, projected, sizePx, he, morph, false, drawEyes)
    }
}
