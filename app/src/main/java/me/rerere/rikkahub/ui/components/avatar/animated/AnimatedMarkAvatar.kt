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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
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
    /** Generical eye bottom shade on/off (glides between the two). */
    eyeShade: Boolean = true,
    /** Character traits; null = sensible default for the eye style. */
    personality: Personality? = null,
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
    // Body colour glides on a change; the eye shade below follows it live.
    val bodyColor by androidx.compose.animation.animateColorAsState(
        targetValue = MarkColors.bodyColor(colorHex, colorPreset, eyeType),
        animationSpec = androidx.compose.animation.core.tween(380),
        label = "markBody",
    )
    val eyeColor = MarkColors.eyeColor(eyeType, eyeColorHex, colorHex, colorPreset)
    val shadeStrength by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (eyeShade) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(380),
        label = "eyeShade",
    )
    val eyeFade = MarkColors.genericalEyeFade(bodyColor, eyeColor, shadeStrength)

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
        motionState.personality = personality ?: Personality.defaultFor(eyeType)
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

/** Thinnest a Grok eye gets when the lids are shut (fraction of the open height). */
internal const val GROK_SLIT = 0.12f

/** Clear margin kept between the eyes and the silhouette edge (fraction of mark radius). */
internal const val GROK_EDGE_MARGIN = 0.20f
internal const val GEN_EDGE_MARGIN = 0.21f

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

/**
 * Character-moment spins (engine12) are drawn as a card turn: the silhouette narrows by
 * |cos(spin)| about the centre while the real 3D yaw only leans within the shape's normal
 * turn cap (shading and eyes slide toward the turn), and the eyes are hidden while the back
 * faces the viewer. Every shape keeps its own outline through the whole spin — a true 3D
 * yaw past ~20° lumps the heart and runs the seated eyes edge-on into slivers.
 */
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
    if (kotlin.math.abs(f.spinYaw) < 0.5f) {
        drawMarkInner(shape, shapeId, isGrok, f, calm, morph, body, eye, eyeFade)
        return
    }
    val sr = Math.toRadians(f.spinYaw.toDouble())
    val c = kotlin.math.cos(sr).toFloat()
    val sn = kotlin.math.sin(sr).toFloat()
    val (yawCap, _) = turnCapFor(shapeId, shape.solid.kind)
    val f2 = f.copy(spinYaw = 0f, body = f.body.copy(yaw = f.body.yaw + yawCap * 0.9f * sn))
    val back = c < 0.04f
    // Eyes fade out over the last sliver before edge-on, so they never pop.
    val eyeK = ((c - 0.04f) / 0.2f).coerceIn(0f, 1f)
    scale(scaleX = kotlin.math.max(kotlin.math.abs(c), 0.06f), scaleY = 1f, pivot = center) {
        drawMarkInner(shape, shapeId, isGrok, f2, calm, morph, body,
            if (back) Color.Transparent else eye.copy(alpha = eye.alpha * eyeK),
            if (back) Color.Transparent else eyeFade.copy(alpha = eyeFade.alpha * eyeK))
    }
}

private fun DrawScope.drawMarkInner(
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
    // Visual turn cap (engine10): total body + head turn is soft-limited per shape so the
    // silhouette stays recognisable (the heart's 3D projection lumps past ~14°) and the
    // eyes keep their inset. tanh keeps it springy instead of a hard stop.
    val (yawCap, pitchCap) = turnCapFor(shape.id, bodyKind)
    val pitch = softCap(f.body.pitch + look.pitchDeg, pitchCap)
    val yaw = softCap(f.body.yaw + look.yawDeg, yawCap)
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
        // A closed Grok eye is a thin slit, never nothing: the eyes must not vanish
        // (and pop back) during blinks or blink-masked expression changes.
        val lidG = lid.coerceAtLeast(GROK_SLIT)
        val lidForPair = if (bodyKind == "sphere") lidG * f.eyeScale.coerceIn(0.5f, 1.2f) else lidG
        val pair = WhSphereEyes.morphPair(f.exprFrom, f.exprTo, f.exprT, lidForPair)
            .mapIndexed { i, e -> FaceRig.shapeCapsule(e, i, f.face.eye(i)) }
        val safeBody = EyeSafeRegion.Body(projected)
        // The turn applied to the eyes is scaled down only as far as needed to keep
        // them inside the silhouette (body turn itself is untouched).
        fun seatGrok(k: Float) = WhEyeSeat.buildSeatedGrokEyes(
            pickerId = shapeId,
            solidKind = bodyKind,
            he = he,
            gazeX = 0f,
            gazeY = 0f,
            lid = lidG,
            eyeGap = f.eyeGap * 0.92f * WhEyeSeat.grokGapFix(shapeId),
            eyeScale = f.eyeScale,
            timeSec = f.timeSec,
            awake = if (calm) 0f else 1f,
            yawDeg = yaw * k,
            pitchDeg = pitch * k,
            rollDeg = roll,
            shape = shape,
            bodyPath = bodyCentered,
            expressionId = WhSphereEyes.grokNameFor(f.exprTo),
            eyesOverride = pair,
            leadX = f.leadX * k,
            leadY = f.leadY * k,
        )
        var (left, right) = if (bodyKind == "sphere") seatGrok(1f)
            else EyeSafeRegion.fit(safeBody, he * GROK_EDGE_MARGIN, ::seatGrok) { listOf(it.first, it.second) }
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
        // Never let the eyes touch or overlap (Sad ^ ^, closed arcs, mid-morph, turned).
        if (ok(left) && ok(right)) {
            val (dl, dr) = GrokEyeGap.separation(left, right, he, WhEyeSeat.grokGapFix(shapeId))
            if (dl != androidx.compose.ui.geometry.Offset.Zero) {
                left = Path().apply { addPath(left, dl) }
                right = Path().apply { addPath(right, dr) }
            }
        }
        if (ok(left) && ok(right)) {
            val (k, c) = EyeSafeRegion.shrink(safeBody, listOf(left, right), he * GROK_EDGE_MARGIN)
            if (k < 1f) {
                // Shrinking about the pair centre narrows the gap a little, so the gap
                // constraint runs again afterwards.
                left = EyeSafeRegion.scalePath(left, k, c)
                right = EyeSafeRegion.scalePath(right, k, c)
                val (dl, dr) = GrokEyeGap.separation(left, right, he, WhEyeSeat.grokGapFix(shapeId))
                if (dl != androidx.compose.ui.geometry.Offset.Zero) {
                    left = Path().apply { addPath(left, dl) }
                    right = Path().apply { addPath(right, dr) }
                }
            }
        }
        // Shrink first (keeps the pair centre, so gaze motion survives), then slide in.
        if (ok(left) && ok(right)) {
            val n = EyeSafeRegion.nudge(safeBody, listOf(left, right), he * GROK_EDGE_MARGIN)
            if (n != androidx.compose.ui.geometry.Offset.Zero) {
                left = Path().apply { addPath(left, n) }
                right = Path().apply { addPath(right, n) }
            }
        }
        if (AvatarDebugMetrics.enabled && ok(left) && ok(right)) {
            val a = GrokEyeGap.outline(left); val b = GrokEyeGap.outline(right)
            val c = AvatarDebugMetrics.centroid(listOf(left, right)) - safeBody.center
            AvatarDebugMetrics.rows += "${AvatarDebugMetrics.label},grok,${shape.id},${f.exprFrom},${f.exprTo},${f.exprT}," +
                "${GrokEyeGap.clearance(a, b) / he},${EyeSafeRegion.inset(safeBody, listOf(left, right), he * 0.5f) / he},${c.x / he},${c.y / he}"
        }
        Soft3DMark.withBodyTransform(this, clip, sizePx, he, morph, false) {
            if (ok(left)) drawPath(left, eye)
            if (ok(right)) drawPath(right, eye)
        }
        return
    }

    // Generical: morph → seat on the surface → eye-colour fill fading into the surface tint.
    val closure = (1f - lid).coerceIn(0f, 1f)
    val lo = GenericalFace.outline(0, f.exprFrom, f.exprTo, f.exprT, closure) ?: return
    val ro = GenericalFace.outline(1, f.exprFrom, f.exprTo, f.exprT, closure) ?: return
    FaceRig.shapeOutline(lo.x, lo.y, 0, f.face.left)
    FaceRig.shapeOutline(ro.x, ro.y, 1, f.face.right)
    val gap = WhEyeSeat.genericalGapScale(shapeId) * (f.eyeGap / 0.94f).coerceIn(0.92f, 1.06f)
    val fit = WhEyeSeat.genericalFitScale(shapeId)
    fun seatGen(k: Float) = GenericalSeat.seat(
        shape = shape,
        he = he,
        bodyPath = bodyCentered,
        left = lo,
        right = ro,
        gap = gap,
        fit = fit,
        leadX = f.leadX * k,
        leadY = f.leadY * k,
        pitchDeg = pitch * k,
        yawDeg = yaw * k,
        rollDeg = roll,
    )
    val genBody = EyeSafeRegion.Body(projected)
    var (le, re) = if (bodyKind == "sphere" || calm) seatGen(1f)
        else EyeSafeRegion.fit(genBody, he * GEN_EDGE_MARGIN, ::seatGen) { listOfNotNull(it.first?.path, it.second?.path) }
    if (!calm) {
        val (k, c) = EyeSafeRegion.shrink(genBody, listOfNotNull(le?.path, re?.path), he * GEN_EDGE_MARGIN)
        if (k < 1f) {
            fun sc(o: Offset) = c + (o - c) * k
            fun sh(e: GenericalSeat.SeatedEye?) = e?.let { GenericalSeat.SeatedEye(EyeSafeRegion.scalePath(it.path, k, c), sc(it.gradStart), sc(it.gradEnd)) }
            le = sh(le); re = sh(re)
        }
        val n = EyeSafeRegion.nudge(genBody, listOfNotNull(le?.path, re?.path), he * GEN_EDGE_MARGIN)
        if (n != androidx.compose.ui.geometry.Offset.Zero) {
            fun mv(e: GenericalSeat.SeatedEye?) = e?.let { GenericalSeat.SeatedEye(Path().apply { addPath(it.path, n) }, it.gradStart + n, it.gradEnd + n) }
            le = mv(le); re = mv(re)
        }
    }
    if (AvatarDebugMetrics.enabled) {
        val ps = listOfNotNull(le?.path, re?.path)
        val c = AvatarDebugMetrics.centroid(ps) - genBody.center
        AvatarDebugMetrics.rows += "${AvatarDebugMetrics.label},generical,${shape.id},${f.exprFrom},${f.exprTo},${f.exprT}," +
            "NaN,${EyeSafeRegion.inset(genBody, ps, he * 0.5f) / he},${c.x / he},${c.y / he}"
    }
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


/** Max visual (yaw, pitch) in degrees per shape. */
internal fun turnCapFor(shapeId: String, bodyKind: String): Pair<Float, Float> = when {
    shapeId.contains("heart", ignoreCase = true) -> 13f to 10f
    bodyKind == "sphere" -> 22f to 15f
    else -> 17f to 12f
}

internal fun softCap(v: Float, cap: Float): Float = cap * kotlin.math.tanh(v / cap)
