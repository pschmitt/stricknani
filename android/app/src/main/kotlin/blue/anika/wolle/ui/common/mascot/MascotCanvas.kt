package blue.anika.wolle.ui.common.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import blue.anika.wolle.R
import blue.anika.wolle.ui.common.burstParticles
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Ball = Color(0xFF8CB8FF)
private val Wrap = Color(0xFF2F6FED)
private val Ink = Color(0xFF16233F)
private val Pink = Color(0xFFFF6F9F)
private val Cream = Color(0xFFFFF1C9)

private const val TILT = -8f
private const val CENTER = 54f
private const val RADIUS = 27f

/** The 7-tap confetti shows once per process; it would be tedious a second time. */
private val confettiShown = AtomicBoolean(false)

/**
 * All the animated values of one mascot. Everything the draw phase reads is Compose state, so
 * animating never recomposes; the physics lives in plain fields and is stepped by [step].
 */
private class MascotAnim(private val rand: () -> Float) {
    var dragX by mutableFloatStateOf(0f)
    var dragY by mutableFloatStateOf(0f)
    var angle by mutableFloatStateOf(0f)
    var look by mutableStateOf(Vec2.Zero)
    var reaction by mutableStateOf<MascotReaction?>(null)
    var reactionT by mutableFloatStateOf(0f)
    var dizzy by mutableStateOf(false)
    var blink by mutableFloatStateOf(0f)
    var fidget by mutableStateOf<Fidget?>(null)
    var fidgetT by mutableFloatStateOf(0f)
    var confettiT by mutableFloatStateOf(-1f)
    var hearts by mutableStateOf<List<HeartParticle>>(emptyList())

    // Physics that never reaches the draw phase directly.
    var grabbed = false
    var targetX = 0f
    var targetY = 0f
    private var velX = 0f
    private var velY = 0f
    private var omega = 0f
    private var angleVel = 0f
    private var spinning = false

    val queue = ReactionQueue(rand)
    val streak = TapStreak()

    /** Called on the main thread when a spin has just stopped. */
    var onSpinStop: () -> Unit = {}

    /** Called when a reaction starts. */
    var onReactionStart: () -> Unit = {}

    val busy: Boolean
        get() =
            grabbed ||
                reaction != null ||
                spinning ||
                fidget != null ||
                confettiT >= 0f ||
                abs(dragX) > 0.01f ||
                abs(dragY) > 0.01f ||
                angle != 0f ||
                look.length > 0.01f

    fun start(r: MascotReaction) {
        reaction = r
        reactionT = 0f
        if (r == MascotReaction.Hearts) hearts = heartParticles(rand)
        onReactionStart()
    }

    fun startFidget(f: Fidget) {
        if (fidget == null) {
            fidget = f
            fidgetT = 0f
        }
    }

    fun spinMore(extra: Float) {
        omega = addSpin(if (spinning) omega else 0f, extra)
        spinning = true
    }

    fun release(vx: Float, vy: Float) {
        grabbed = false
        val flick = flickToOmega(vx, vy)
        if (flick != 0f) spinMore(flick)
    }

    fun step(dt: Float) {
        if (grabbed) {
            dragX = targetX
            dragY = targetY
            velX = 0f
            velY = 0f
        } else {
            val sx = springStep(Spring1(dragX, velX), 0f, dt)
            val sy = springStep(Spring1(dragY, velY), 0f, dt)
            velX = sx.vel
            velY = sy.vel
            dragX = sx.pos
            dragY = sy.pos
            if (isSettled(sx) && isSettled(sy)) {
                dragX = 0f
                dragY = 0f
                velX = 0f
                velY = 0f
            }
        }

        if (spinning) {
            angle += omega * dt
            omega = frictionStep(omega, dt)
            if (abs(omega) < SPIN_MIN_OMEGA) {
                spinning = false
                angleVel = omega
                omega = 0f
                onSpinStop()
                start(MascotReaction.Giggle)
            }
        } else if (angle != 0f || angleVel != 0f) {
            val rest = (angle / 360f).roundToInt() * 360f
            val s = springStep(Spring1(angle - rest, angleVel), 0f, dt, 220f, 11f)
            angle = rest + s.pos
            angleVel = s.vel
            if (isSettled(s, posEps = 0.3f, velEps = 6f)) {
                angle = 0f
                angleVel = 0f
            }
        }
        dizzy = spinning && isDizzy(omega)

        reaction?.let { current ->
            reactionT += dt * 1000f / current.durationMillis
            if (reactionT >= 1f) {
                reaction = null
                reactionT = 0f
                queue.finish()?.let { start(it) }
            }
        }
        if (reaction == null && queue.current != null) queue.finish()

        fidget?.let { f ->
            fidgetT += dt * 1000f / f.durationMillis
            if (fidgetT >= 1f) {
                fidget = null
                fidgetT = 0f
                blink = 0f
            } else if (f == Fidget.Blink) {
                blink = sin(PI.toFloat() * fidgetT)
            }
        }

        if (confettiT >= 0f) {
            confettiT += dt / CONFETTI_SECONDS
            if (confettiT >= 1f) confettiT = -1f
        }

        val target =
            when {
                grabbed -> lookDirection(dragX, dragY)
                fidget == Fidget.LookAround ->
                    Vec2(
                        sin(2f * PI.toFloat() * fidgetT) * sin(PI.toFloat() * fidgetT) * 1.6f,
                        -0.15f,
                    )
                else -> Vec2.Zero
            }
        val k = 1f - exp(-14f * dt)
        look = Vec2(look.x + (target.x - look.x) * k, look.y + (target.y - look.y) * k)
        if (target == Vec2.Zero && look.length < 0.01f) look = Vec2.Zero
    }
}

/** A one-off emotion an outside event asks the mascot to play. */
enum class MascotCueKind {
    /** A happy hop with hearts: something went well. */
    Happy,
    /** A small sad droop: something went wrong. */
    Sad,
}

/**
 * Plays [kind] once. A plain class on purpose: every new instance is a new cue, even for the same
 * kind, so a second success plays again.
 */
class MascotCue(val kind: MascotCueKind)

/**
 * The yarn-ball mascot drawn as a Canvas in the launcher's 108-unit geometry, so the face can
 * animate. [size] is the box; the ball is about 60% of it. [bob] adds a slow sway. With [reactive]
 * it reacts to taps, can be dragged and flicked, and fidgets while idle; [exposeSemantics] adds a
 * Button node ("Say hi") and turns it off where the mascot sits inside another clickable (it still
 * plays its reaction there, without becoming a second focus target). Reduced motion (animator scale
 * 0) only blinks the mascot on tap.
 */
@Composable
internal fun MascotCanvas(
    size: Dp,
    modifier: Modifier,
    bob: Boolean,
    reactive: Boolean,
    exposeSemantics: Boolean,
    onReact: (() -> Unit)?,
    reduced: Boolean,
    sway: () -> Float,
    busy: Boolean = false,
    cue: MascotCue? = null,
) {
    val rand = remember { Random.Default.let { r -> { r.nextFloat() } } }
    val anim = remember { MascotAnim(rand) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val flash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentOnReact by rememberUpdatedState(onReact)
    val lifecycleOwner = LocalLifecycleOwner.current
    val still = !bob || reduced

    anim.onReactionStart = { haptics.tick() }
    anim.onSpinStop = { haptics.tick() }

    // The frame loop only runs while something moves; idle mascots cost nothing.
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        for (ignored in wake) {
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (isActive && anim.busy) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                anim.step(dt)
            }
        }
    }

    LaunchedEffect(reactive, reduced, lifecycleOwner) {
        if (!reactive || reduced) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(nextFidgetDelay(rand))
                if (!anim.grabbed && anim.reaction == null && anim.fidget == null) {
                    anim.startFidget(pickFidget(rand))
                    wake.trySend(Unit)
                }
            }
        }
    }

    // Outside-driven moods: a looping "working" rock while busy, and one-off hop / droop cues.
    // Reduced motion plays none of them.
    val busyPhase = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    val droop = remember { Animatable(0f) }
    LaunchedEffect(busy, reduced) {
        if (!busy || reduced) {
            if (busyPhase.value != 0f) {
                busyPhase.animateTo(busyPhase.value.roundToInt().toFloat(), tween(200))
                busyPhase.snapTo(0f)
            }
            return@LaunchedEffect
        }
        while (isActive) {
            busyPhase.snapTo(0f)
            busyPhase.animateTo(1f, tween(BUSY_LOOP_MILLIS, easing = LinearEasing))
        }
    }
    LaunchedEffect(cue, reduced) {
        val current = cue ?: return@LaunchedEffect
        if (reduced) return@LaunchedEffect
        when (current.kind) {
            MascotCueKind.Happy -> {
                anim.start(MascotReaction.Hearts)
                wake.trySend(Unit)
                hop.snapTo(0f)
                hop.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
                hop.animateTo(
                    0f,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            MascotCueKind.Sad -> {
                droop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                delay(650)
                droop.animateTo(0f, tween(450))
            }
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val onTap: () -> Unit = {
        currentOnReact?.invoke()
        if (reduced) {
            scope.launch {
                flash.snapTo(1f)
                flash.animateTo(0f, tween(180))
            }
        } else {
            val count = anim.streak.tap(System.currentTimeMillis())
            if (anim.streak.isEasterEgg(count) && confettiShown.compareAndSet(false, true)) {
                anim.confettiT = 0f
            } else if (count >= SPIN_STREAK_TAPS) {
                anim.spinMore(if (count % 2 == 0) 420f else -420f)
            }
            anim.queue.request()?.let { anim.start(it) }
            wake.trySend(Unit)
        }
    }

    var interaction: Modifier = Modifier
    if (reactive) {
        if (exposeSemantics) {
            val label = stringResource(R.string.mascot_say_hi)
            interaction = interaction.semantics {
                role = Role.Button
                contentDescription = label
                onClick {
                    onTap()
                    true
                }
            }
        }
        interaction =
            interaction.pointerInput(reduced) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(this.size.width / 2f, this.size.height / 2f)
                    val hit = this.size.width * (RADIUS / 108f) * 1.25f
                    if ((down.position - center).getDistance() > hit) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!dragging) {
                            // A scroll parent (or anyone else) took the gesture: not ours.
                            if (change.isConsumed) return@awaitEachGesture
                            if (!change.pressed) {
                                onTap()
                                return@awaitEachGesture
                            }
                            if ((change.position - down.position).getDistance() > slop) {
                                if (reduced) return@awaitEachGesture
                                dragging = true
                                anim.grabbed = true
                                haptics.tick()
                                wake.trySend(Unit)
                            } else {
                                continue
                            }
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            val v = tracker.calculateVelocity()
                            anim.release(v.x / density.density, v.y / density.density)
                            haptics.tick()
                            wake.trySend(Unit)
                            return@awaitEachGesture
                        }
                        change.consume()
                        val raw =
                            Vec2(
                                (change.position.x - down.position.x) / density.density,
                                (change.position.y - down.position.y) / density.density,
                            )
                        val banded = rubberBand(raw, DRAG_LIMIT_DP)
                        anim.targetX = banded.x
                        anim.targetY = banded.y
                    }
                    if (dragging) {
                        anim.release(0f, 0f)
                        wake.trySend(Unit)
                    }
                }
            }
    }

    Canvas(
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    var rotate = 0f
                    var lift = 0f
                    if (!still) {
                        val s = sway()
                        rotate = s
                        lift = s * 0.6f
                    }
                    // Busy: a gentle roll to and fro with a little bob.
                    val phase = busyPhase.value
                    if (phase != 0f) {
                        val wave = sin(2f * PI.toFloat() * phase)
                        rotate += 12f * wave
                        lift -= 3.dp.toPx() * (1f - cos(4f * PI.toFloat() * phase)) / 2f
                    }
                    val sad = droop.value
                    rotate -= 7f * sad
                    lift += 4.dp.toPx() * sad - 14.dp.toPx() * hop.value
                    rotationZ = rotate
                    translationY = lift
                    transformOrigin = TransformOrigin(0.5f, 0.85f)
                    scaleY = 1f - 0.1f * sad
                    scaleX = 1f + 0.05f * sad
                }
                .then(interaction)
    ) {
        drawMascot(anim, density.density, size.toPx() / 108f, 1f - 0.4f * flash.value)
    }
}

private const val BUSY_LOOP_MILLIS = 1100

private fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)

private fun DrawScope.drawMascot(anim: MascotAnim, dpScale: Float, u: Float, alpha: Float) {
    val reaction = anim.reaction
    val t = anim.reactionT
    // Reaction motion in 108-units.
    var scaleX = 1f
    var scaleY = 1f
    var hop = 0f
    var extraRotation = 0f
    when (reaction) {
        MascotReaction.Squish -> {
            val w = wobble(t, 1.7f, 3.2f) * 0.3f
            scaleX = 1f - w
            scaleY = 1f + w
        }
        MascotReaction.WinkSwap -> extraRotation = 6f * sin(PI.toFloat() * t)
        MascotReaction.Spin -> {
            extraRotation = 360f * easeOutBack(t)
            hop = 8f * sin(PI.toFloat() * t)
        }
        MascotReaction.Hearts -> hop = 3f * sin(PI.toFloat() * (t * 2f).coerceAtMost(1f))
        MascotReaction.Giggle -> {
            extraRotation = 9f * wobble(t, 3.2f, 2.4f) * 2f
            scaleY = 1f + 0.04f * wobble(t, 6f, 2f)
        }
        null -> Unit
    }
    if (anim.fidget == Fidget.MicroBounce) hop += 3.5f * sin(PI.toFloat() * anim.fidgetT)
    val giggle = giggleAmount(reaction, t)
    val rotation = TILT + anim.angle + extraRotation
    val pivot = Offset(CENTER, CENTER)
    val ground = Offset(CENTER, CENTER + RADIUS)

    // Drag stretch: along the drag direction, volume-preserving, centre follows a bit.
    val unitsPerDp = dpScale.let { 1f / u * it }
    val dragDx = anim.dragX * unitsPerDp
    val dragDy = anim.dragY * unitsPerDp
    val dragLen = kotlin.math.hypot(anim.dragX, anim.dragY)
    val stretch = 1f + 0.5f * (dragLen / DRAG_LIMIT_DP)
    val dragAngle = Math.toDegrees(atan2(anim.dragY, anim.dragX).toDouble()).toFloat()

    // Look vector is in screen space; the face lives in the rotated frame.
    val rad = Math.toRadians(-rotation.toDouble())
    val lx = (anim.look.x * cos(rad) - anim.look.y * sin(rad)).toFloat()
    val ly = (anim.look.x * sin(rad) + anim.look.y * cos(rad)).toFloat()

    withTransform({
        scale(u, u, Offset.Zero)
        translate(dragDx * 0.55f, dragDy * 0.55f - hop)
        if (dragLen > 0.01f) {
            rotate(dragAngle, pivot)
            scale(stretch, 1f / kotlin.math.sqrt(stretch), pivot)
            rotate(-dragAngle, pivot)
        }
        scale(scaleX, scaleY, ground)
        rotate(rotation, pivot)
    }) {
        drawBall(alpha, lx, ly, anim, giggle, eyes = eyePose(reaction, t, anim.dizzy))
    }

    withTransform({ scale(u, u, Offset.Zero) }) {
        if (reaction == MascotReaction.Hearts) drawHearts(anim.hearts, t)
        if (anim.confettiT >= 0f) drawConfetti(anim.confettiT)
    }
}

private fun DrawScope.drawBall(
    alpha: Float,
    lx: Float,
    ly: Float,
    anim: MascotAnim,
    giggle: Float,
    eyes: Eyes,
) {
    val center = Offset(CENTER, CENTER)
    drawCircle(Ball, RADIUS, center, alpha)
    val clip = Path().apply { addOval(Rect(center, RADIUS)) }
    withTransform({ clipPath(clip) }) {
        val wrapStyle = Stroke(7f, cap = StrokeCap.Round)
        translate(lx * 1.5f, ly * 1.5f) {
            drawPath(
                Path().apply {
                    moveTo(24f, 38f)
                    quadraticTo(54f, 27f, 86f, 42f)
                },
                Wrap,
                alpha,
                wrapStyle,
            )
            drawPath(
                Path().apply {
                    moveTo(22f, 70f)
                    quadraticTo(56f, 88f, 88f, 64f)
                },
                Wrap,
                alpha,
                wrapStyle,
            )
        }
    }

    // Eyes.
    val ex = lx * 3.2f
    val ey = ly * 2.4f
    drawEye(
        eyes.left,
        45f + ex,
        51f + ey,
        alpha,
        if (eyes.left == EyeShape.Dot) 1f - 0.88f * anim.blink else 1f,
    )
    drawEye(eyes.right, 62.5f + ex, 51f + ey, alpha, 1f)

    // Smile.
    val mx = lx * 2.2f
    val my = ly * 1.6f
    val smile =
        Path().apply {
            moveTo(45f - 2f * giggle + mx, 60f + my)
            quadraticTo(54f + mx, 74f + 5f * giggle + my, 63f + 2f * giggle + mx, 60f + my)
            close()
        }
    drawPath(smile, Ink, alpha, Fill)
    drawPath(smile, Ink, alpha, Stroke(2f, join = StrokeJoin.Round))

    // Cheeks, rosier when giggling.
    val cx = lx * 1.6f
    val cy = ly * 1.2f
    val cheekRadius = 4.2f * (1f + 0.3f * giggle)
    for (x in floatArrayOf(38f, 70f)) {
        if (giggle > 0f)
            drawCircle(Pink, cheekRadius * 1.7f, Offset(x + cx, 60f + cy), 0.3f * giggle * alpha)
        drawCircle(Pink, cheekRadius, Offset(x + cx, 60f + cy), alpha)
    }
}

private fun DrawScope.drawEye(shape: EyeShape, cx: Float, cy: Float, alpha: Float, open: Float) {
    when (shape) {
        EyeShape.Dot -> {
            val h = 3.8f * open
            drawOval(
                Ink,
                Offset(cx - 3.8f, cy - h),
                androidx.compose.ui.geometry.Size(7.6f, 2f * h),
                alpha,
            )
        }
        EyeShape.Arc ->
            drawPath(
                Path().apply {
                    moveTo(cx - 4.5f, cy + 1f)
                    quadraticTo(cx - 0.5f, cy - 5f, cx + 4.5f, cy + 1f)
                },
                Ink,
                alpha,
                Stroke(3.6f, cap = StrokeCap.Round),
            )
        EyeShape.Cross -> {
            val s = Stroke(3f, cap = StrokeCap.Round)
            drawLine(
                Ink,
                Offset(cx - 3f, cy - 3f),
                Offset(cx + 3f, cy + 3f),
                s.width,
                StrokeCap.Round,
                alpha = alpha,
            )
            drawLine(
                Ink,
                Offset(cx + 3f, cy - 3f),
                Offset(cx - 3f, cy + 3f),
                s.width,
                StrokeCap.Round,
                alpha = alpha,
            )
        }
    }
}

private fun heartPath(cx: Float, cy: Float, s: Float) =
    Path().apply {
        moveTo(cx, cy + s * 0.9f)
        cubicTo(cx - s * 1.4f, cy + s * 0.1f, cx - s * 0.9f, cy - s * 1.0f, cx, cy - s * 0.35f)
        cubicTo(cx + s * 0.9f, cy - s * 1.0f, cx + s * 1.4f, cy + s * 0.1f, cx, cy + s * 0.9f)
        close()
    }

private fun DrawScope.drawHearts(hearts: List<HeartParticle>, t: Float) {
    for (h in hearts) {
        val tt = ((t - h.delayFraction) / (1f - h.delayFraction)).coerceIn(0f, 1f)
        if (tt <= 0f) continue
        val x = CENTER + h.dx + h.drift * sin(tt * 2f * PI.toFloat()) * 3f
        val y = 32f - 34f * (1f - (1f - tt) * (1f - tt))
        drawPath(heartPath(x, y, h.size), Pink, (1f - tt * tt).coerceIn(0f, 1f), Fill)
    }
}

private val confetti = burstParticles(14, 34f, 54f)
private val confettiColors = listOf(Ball, Wrap, Pink, Cream)

private fun DrawScope.drawConfetti(tt: Float) {
    val e = 1f - (1f - tt) * (1f - tt)
    val a = (1f - max(0f, (tt - 0.6f) / 0.4f)).coerceIn(0f, 1f)
    confetti.forEachIndexed { i, p ->
        val c = Offset(CENTER + p.dx * e, CENTER + p.dy * e + 26f * tt * tt)
        val color = confettiColors[i % confettiColors.size]
        val r = p.size * 0.45f
        drawCircle(color, r, c, a)
        drawLine(
            Ink,
            Offset(c.x - r * 0.7f, c.y - r * 0.2f),
            Offset(c.x + r * 0.7f, c.y + r * 0.35f),
            0.7f,
            alpha = a * 0.6f,
        )
    }
}
