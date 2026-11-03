<script lang="ts" module>
  import { clampEyeOffset, EYE_MAX, lookDirection } from "./mascot/physics.ts";

  // The easter egg plays once per page session.
  let confettiPlayed = false;

  // One shared pointer listener feeds every mascot's eye tracking.
  type Follower = (x: number, y: number) => void;
  const followers = new Set<Follower>();
  let pointerX = 0;
  let pointerY = 0;
  let pointerFrame = 0;

  function onWindowPointerMove(event: PointerEvent) {
    if (event.pointerType === "touch") return;
    pointerX = event.clientX;
    pointerY = event.clientY;
    if (pointerFrame) return;
    pointerFrame = requestAnimationFrame(() => {
      pointerFrame = 0;
      for (const follower of followers) follower(pointerX, pointerY);
    });
  }

  function follow(follower: Follower): () => void {
    if (followers.size === 0) {
      window.addEventListener("pointermove", onWindowPointerMove, {
        passive: true,
      });
    }
    followers.add(follower);
    return () => {
      followers.delete(follower);
      if (followers.size === 0) {
        window.removeEventListener("pointermove", onWindowPointerMove);
        if (pointerFrame) cancelAnimationFrame(pointerFrame);
        pointerFrame = 0;
      }
    };
  }

  /** Eye offset (viewBox units) for a pointer at (px, py) and a ball centred at (cx, cy). */
  export function eyeOffsetFor(px: number, py: number, cx: number, cy: number) {
    const dir = lookDirection(px - cx, py - cy);
    return clampEyeOffset(dir.x * EYE_MAX, dir.y * EYE_MAX);
  }
</script>

<script lang="ts">
  import { onMount } from "svelte";
  import { t } from "#lib/i18n.svelte.ts";
  import {
    addSpin,
    flickToOmega,
    isDizzy,
    isSettled,
    lookDirection as lookDir,
    nextFidgetDelay,
    pickFidget,
    RUBBER_LIMIT,
    rubberBand2d,
    springStep,
    spinStep,
    type Spin,
    type Spring,
  } from "./mascot/physics.ts";
  import {
    advancePlayback,
    confettiParticles,
    heartParticles,
    idlePlayback,
    initialTapState,
    nextReaction,
    poseAt,
    REACTION_DURATION,
    registerTap,
    requestReaction,
    restPose,
    type EyeMode,
    type Particle,
    type Playback,
    type Reaction,
    type TapState,
  } from "./mascot/reactions.ts";

  // The yarn-ball mascot "Winky Pom", drawn inline so its parts can move.
  // `bob` adds a short, gentle sway (a greeting moment, not a permanent
  // animation); `roll` turns it like a ball of yarn while something loads.
  // `reactive` (default) makes it respond to taps, hovering, dragging and
  // idle fidgets. `interactive` chooses how: "button" is a real button with a
  // label; "decorative" has no tab stop and no drag, for use inside a link
  // that is itself the one focus target (the click still bubbles to the link).
  let {
    size = 96,
    bob = false,
    roll = false,
    reactive = true,
    interactive = "button",
  }: {
    size?: number;
    bob?: boolean;
    roll?: boolean;
    reactive?: boolean;
    interactive?: "button" | "decorative";
  } = $props();

  const uid = $props.id();
  const clipId = `mascot-clip-${uid}`;
  const HEART_PATH =
    "M20.8 8.8c0 5.7-8.8 10.3-8.8 10.3S3.2 14.5 3.2 8.8a4.6 4.6 0 0 1 8.8-2.1 4.6 4.6 0 0 1 8.8 2.1Z";

  // The little highlight stroke across a confetti yarn ball.
  function yarnArc(r: number): string {
    return `M${-r * 0.7} ${-r * 0.1}Q0 ${-r * 0.8} ${r * 0.7} ${r * 0.1}`;
  }

  const asButton = $derived(reactive && interactive === "button");
  const label = $derived(t("Say hi to the yarn ball"));

  let root: HTMLElement | undefined = $state();
  let ballEl: SVGGElement | undefined = $state();
  let wrapsEl: SVGGElement | undefined = $state();
  let faceEl: SVGGElement | undefined = $state();
  let eyesEl: SVGGElement | undefined = $state();

  let eyeMode = $state<EyeMode>("normal");
  let cheek = $state(0);
  let blinking = $state(false);
  let particles = $state<(Particle & { id: number })[]>([]);

  // Everything below is plain mutable state driven by the animation loop.
  let reduced = false;
  let visible = true;
  let frame = 0;
  let lastTime = 0;
  let playback: Playback = idlePlayback;
  let startedReaction: Playback["current"] = null;
  let lastReaction: Reaction | null = null;
  let tapState: TapState = initialTapState;
  let pos: Spring[] = [
    { x: 0, v: 0 },
    { x: 0, v: 0 },
  ];
  let spin: Spin = { angle: 0, omega: 0 };
  let wasFast = false;
  let eye = { x: 0, y: 0 };
  let eyeTarget = { x: 0, y: 0 };
  let bounceAt: number | null = null;
  let blinkUntil = 0;
  let lookSweepAt: number | null = null;
  let particleId = 0;

  type Drag = {
    pointerId: number;
    startX: number;
    startY: number;
    dragging: boolean;
    samples: { t: number; x: number; y: number }[];
  };
  let drag: Drag | null = null;
  let pull = { x: 0, y: 0 };

  function ensureLoop() {
    if (frame || !ballEl) return;
    lastTime = performance.now();
    frame = requestAnimationFrame(tick);
  }

  function spawn(list: Particle[], lifetime: number) {
    const tagged = list.map((p) => ({ ...p, id: (particleId += 1) }));
    particles = [...particles, ...tagged];
    const ids = new Set(tagged.map((p) => p.id));
    setTimeout(() => {
      particles = particles.filter((p) => !ids.has(p.id));
    }, lifetime);
  }

  function tick() {
    frame = 0;
    if (!ballEl) return;
    const now = performance.now();
    const dt = Math.min(0.05, Math.max(0.001, (now - lastTime) / 1000));
    lastTime = now;

    playback = advancePlayback(playback, now);
    if (playback.current && playback.current !== startedReaction) {
      startedReaction = playback.current;
      if (playback.current.kind === "hearts") {
        spawn(heartParticles(Math.random), 1300);
      }
    }
    if (!playback.current) startedReaction = null;

    let pose = restPose;
    if (playback.current) {
      const { kind, startedAt } = playback.current;
      pose = poseAt(kind, (now - startedAt) / REACTION_DURATION[kind]);
    } else if (bounceAt !== null) {
      const p = (now - bounceAt) / REACTION_DURATION.bounce;
      if (p >= 1) bounceAt = null;
      else pose = poseAt("bounce", p);
    }

    // Drag target or spring back to rest.
    if (drag?.dragging) {
      const banded = rubberBand2d(pull.x, pull.y, RUBBER_LIMIT);
      pos = [
        { x: banded.x, v: 0 },
        { x: banded.y, v: 0 },
      ];
    } else {
      pos = [springStep(pos[0], 0, dt), springStep(pos[1], 0, dt)];
    }

    if (!drag?.dragging) spin = spinStep(spin, dt);
    const fast = isDizzy(spin.omega);
    if (wasFast && Math.abs(spin.omega) < 90 && !fast) {
      playback = requestReaction(playback, "giggle", now);
    }
    wasFast = wasFast ? Math.abs(spin.omega) >= 90 : fast;

    // Eyes: look toward the drag, else follow the pointer.
    let target = eyeTarget;
    let faceShift = { x: 0, y: 0 };
    if (drag?.dragging) {
      const dir = lookDir(pull.x, pull.y, RUBBER_LIMIT);
      target = { x: dir.x * 1.5, y: dir.y * 1.5 };
      faceShift = { x: dir.x * 2.4, y: dir.y * 1.8 };
    } else if (lookSweepAt !== null) {
      const p = (now - lookSweepAt) / 1100;
      if (p >= 1) lookSweepAt = null;
      else target = { x: 1.5 * Math.sin(p * Math.PI * 2), y: -0.4 };
    }
    const ease = Math.min(1, dt * 14);
    eye = {
      x: eye.x + (target.x - eye.x) * ease,
      y: eye.y + (target.y - eye.y) * ease,
    };

    let mode: EyeMode = pose.eyes;
    if (fast) mode = "dizzy";
    else if (mode === "normal" && now < blinkUntil) mode = "blink";
    if (mode !== eyeMode) eyeMode = mode;
    const rosy = Math.round(Math.max(pose.cheek, fast ? 0.6 : 0) * 20) / 20;
    if (rosy !== cheek) cheek = rosy;

    // Apply to the SVG parts.
    const unit = 66 / size;
    const tx = pos[0].x * unit * 0.5;
    const ty = pos[1].x * unit * 0.5 + pose.dy;
    const len = Math.hypot(pos[0].x, pos[1].x) / RUBBER_LIMIT;
    const theta = (Math.atan2(pos[1].x, pos[0].x) * 180) / Math.PI;
    const stretch = 0.18 * len;
    ballEl.setAttribute(
      "transform",
      `translate(${tx.toFixed(2)} ${ty.toFixed(2)}) ` +
        `rotate(${(spin.angle + pose.rot).toFixed(2)} 54 54) ` +
        `translate(54 81) scale(${pose.sx.toFixed(3)} ${pose.sy.toFixed(3)}) translate(-54 -81) ` +
        `translate(54 54) rotate(${theta.toFixed(1)}) ` +
        `scale(${(1 + stretch).toFixed(3)} ${(1 - stretch * 0.7).toFixed(3)}) ` +
        `rotate(${(-theta).toFixed(1)}) translate(-54 -54)`,
    );
    wrapsEl?.setAttribute(
      "transform",
      `rotate(${(-pos[0].x * 0.5 - pose.rot * 0.3).toFixed(2)} 54 54)`,
    );
    faceEl?.setAttribute(
      "transform",
      `translate(${faceShift.x.toFixed(2)} ${faceShift.y.toFixed(2)})`,
    );
    eyesEl?.setAttribute(
      "transform",
      `translate(${eye.x.toFixed(2)} ${eye.y.toFixed(2)})`,
    );

    const busy =
      drag !== null ||
      playback.current !== null ||
      playback.queued !== null ||
      bounceAt !== null ||
      lookSweepAt !== null ||
      now < blinkUntil ||
      !isSettled(
        { x: pos[0].x, y: pos[1].x },
        { x: pos[0].v, y: pos[1].v },
        spin,
      ) ||
      Math.hypot(eye.x - target.x, eye.y - target.y) > 0.02;
    if (busy) {
      frame = requestAnimationFrame(tick);
    } else {
      spin = { angle: 0, omega: 0 };
      wasFast = false;
      eyeMode = "normal";
    }
  }

  function playReducedBlink() {
    blinking = true;
    setTimeout(() => (blinking = false), 220);
  }

  function tap() {
    if (reduced) {
      playReducedBlink();
      return;
    }
    const now = performance.now();
    const result = registerTap(tapState, now, !confettiPlayed);
    tapState = result.state;
    if (result.easterEgg) {
      confettiPlayed = true;
      spawn(confettiParticles(Math.random), 1500);
      playback = requestReaction(playback, "squish", now);
    } else {
      const kind = nextReaction(lastReaction, Math.random);
      lastReaction = kind;
      playback = requestReaction(playback, kind, now);
      if (result.quick && tapState.count >= 3) {
        spin = { ...spin, omega: addSpin(spin.omega, 220) };
      }
    }
    ensureLoop();
  }

  function onPointerDown(event: PointerEvent) {
    if (reduced || (event.pointerType === "mouse" && event.button !== 0)) {
      return;
    }
    drag = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      dragging: false,
      samples: [{ t: performance.now(), x: event.clientX, y: event.clientY }],
    };
  }

  function onPointerMove(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const dx = event.clientX - drag.startX;
    const dy = event.clientY - drag.startY;
    if (!drag.dragging) {
      if (Math.hypot(dx, dy) < 5) return;
      drag.dragging = true;
      try {
        (event.currentTarget as Element).setPointerCapture(event.pointerId);
      } catch {
        // Capture is a nicety; dragging still works while over the ball.
      }
      spin = { angle: Math.round(spin.angle / 360) * 360, omega: 0 };
      ensureLoop();
    }
    pull = { x: dx, y: dy };
    const now = performance.now();
    drag.samples.push({ t: now, x: event.clientX, y: event.clientY });
    drag.samples = drag.samples.filter((s) => now - s.t <= 90);
    event.preventDefault();
  }

  function onPointerUp(event: PointerEvent) {
    if (!drag || event.pointerId !== drag.pointerId) return;
    const finished = drag;
    drag = null;
    if (!finished.dragging) {
      tap();
      return;
    }
    const first = finished.samples[0];
    const lastSample = finished.samples[finished.samples.length - 1];
    const span = Math.max(16, lastSample.t - first.t) / 1000;
    const omega = flickToOmega(
      (lastSample.x - first.x) / span,
      (lastSample.y - first.y) / span,
    );
    if (omega !== 0) spin = { ...spin, omega: addSpin(spin.omega, omega) };
    // Hand the stretch to the spring so it snaps back with overshoot.
    const banded = rubberBand2d(pull.x, pull.y, RUBBER_LIMIT);
    pos = [
      { x: banded.x, v: 0 },
      { x: banded.y, v: 0 },
    ];
    pull = { x: 0, y: 0 };
    ensureLoop();
  }

  function onPointerCancel() {
    if (drag?.dragging) {
      const banded = rubberBand2d(pull.x, pull.y, RUBBER_LIMIT);
      pos = [
        { x: banded.x, v: 0 },
        { x: banded.y, v: 0 },
      ];
      pull = { x: 0, y: 0 };
      ensureLoop();
    }
    drag = null;
  }

  function onButtonClick(event: MouseEvent) {
    // Pointer taps are handled on pointerup; a click without a pointer is
    // the keyboard (Enter or Space).
    if (event.detail === 0) tap();
  }

  function onDecorativeClick() {
    tap();
  }

  function fidget() {
    if (
      reduced ||
      !visible ||
      document.hidden ||
      drag ||
      playback.current ||
      Math.abs(spin.omega) > 1
    ) {
      return;
    }
    const now = performance.now();
    const kind = pickFidget(Math.random);
    if (kind === "blink") blinkUntil = now + 180;
    else if (kind === "look-around") lookSweepAt = now;
    else bounceAt = now;
    ensureLoop();
  }

  onMount(() => {
    const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
    const finePointer = window.matchMedia("(hover: hover) and (pointer: fine)");
    reduced = motion.matches;
    const onMotion = () => {
      reduced = motion.matches;
    };
    motion.addEventListener("change", onMotion);

    let observer: IntersectionObserver | undefined;
    if (root && "IntersectionObserver" in window) {
      observer = new IntersectionObserver((entries) => {
        visible = entries.some((entry) => entry.isIntersecting);
      });
      observer.observe(root);
    }

    let stopFollowing: (() => void) | undefined;
    if (reactive) {
      stopFollowing = follow((px, py) => {
        if (reduced || !finePointer.matches || !visible || !root) return;
        if (drag?.dragging) return;
        const box = root.getBoundingClientRect();
        const offset = eyeOffsetFor(
          px,
          py,
          box.left + box.width / 2,
          box.top + box.height / 2,
        );
        eyeTarget = offset;
        ensureLoop();
      });
    }

    let idleTimer: ReturnType<typeof setTimeout> | undefined;
    const schedule = () => {
      idleTimer = setTimeout(() => {
        if (reactive) fidget();
        schedule();
      }, nextFidgetDelay(Math.random));
    };
    if (reactive) schedule();

    return () => {
      motion.removeEventListener("change", onMotion);
      observer?.disconnect();
      stopFollowing?.();
      clearTimeout(idleTimer);
      if (frame) cancelAnimationFrame(frame);
      frame = 0;
    };
  });
</script>

{#snippet art()}
  <svg
    class="mascot-art"
    viewBox="21 21 66 66"
    width={size}
    height={size}
    aria-hidden="true"
    focusable="false"
  >
    <defs>
      <clipPath id={clipId}><circle cx="54" cy="54" r="27" /></clipPath>
    </defs>
    <g class="mascot-ball" bind:this={ballEl}>
      <g transform="rotate(-8 54 54)">
        <circle cx="54" cy="54" r="27" fill="#8cb8ff" />
        <g clip-path="url(#{clipId})">
          <g
            class="mascot-wraps"
            bind:this={wrapsEl}
            fill="none"
            stroke="#2f6fed"
            stroke-width="7"
            stroke-linecap="round"
          >
            <path d="M24 38Q54 27 86 42" />
            <path d="M22 70Q56 88 88 64" />
          </g>
        </g>
        <g class="mascot-face" bind:this={faceEl}>
          <g
            class="mascot-eyes"
            bind:this={eyesEl}
            fill="none"
            stroke="#16233f"
            stroke-width="3.6"
            stroke-linecap="round"
          >
            {#if eyeMode === "normal" || eyeMode === "swap" || eyeMode === "blink"}
              {#if eyeMode === "swap"}
                <path d="M40.5 52Q45 46 49.5 52" />
              {:else if eyeMode === "blink"}
                <path d="M41 51.5H49" />
              {:else}
                <circle cx="45" cy="51" r="3.8" fill="#16233f" stroke="none" />
              {/if}
              {#if eyeMode === "swap"}
                <circle
                  cx="62.5"
                  cy="51"
                  r="3.8"
                  fill="#16233f"
                  stroke="none"
                />
              {:else}
                <path d="M58 52Q62 46 67 52" />
              {/if}
            {:else if eyeMode === "happy"}
              <path d="M40.5 52Q45 46 49.5 52" />
              <path d="M58 52Q62 46 67 52" />
            {:else}
              <path d="M42 48L48 54M48 48L42 54" stroke-width="2.6" />
              <path d="M59.5 48L65.5 54M65.5 48L59.5 54" stroke-width="2.6" />
            {/if}
          </g>
          <path
            d="M45 60Q54 74 63 60Z"
            fill="#16233f"
            stroke="#16233f"
            stroke-width="2"
            stroke-linejoin="round"
          />
          <circle
            class="mascot-cheek"
            cx="38"
            cy="60"
            r={4.2 * (1 + 0.3 * cheek)}
            fill={cheek > 0.3 ? "#ff5a8f" : "#ff6f9f"}
          />
          <circle
            class="mascot-cheek"
            cx="70"
            cy="60"
            r={4.2 * (1 + 0.3 * cheek)}
            fill={cheek > 0.3 ? "#ff5a8f" : "#ff6f9f"}
          />
        </g>
      </g>
    </g>
    {#each particles as p (p.id)}
      <g transform="translate({p.x0} {p.kind === 'heart' ? 36 : 54})">
        <g
          class="mascot-particle"
          style:--dx="{p.dx}px"
          style:--dy="{p.dy}px"
          style:--delay="{p.delay}ms"
        >
          {#if p.kind === "heart"}
            <path
              d={HEART_PATH}
              fill={p.color}
              transform="scale({p.size / 24}) translate(-12 -12)"
            />
          {:else}
            <circle r={p.size} fill={p.color} />
            <path
              d={yarnArc(p.size)}
              fill="none"
              stroke="#ffffff"
              stroke-opacity="0.7"
              stroke-width={p.size * 0.25}
              stroke-linecap="round"
            />
          {/if}
        </g>
      </g>
    {/each}
  </svg>
{/snippet}

{#if asButton}
  <button
    bind:this={root}
    type="button"
    class="mascot mascot--live"
    class:mascot--bob={bob}
    class:mascot--roll={roll}
    class:mascot--blink={blinking}
    aria-label={label}
    onpointerdown={onPointerDown}
    onpointermove={onPointerMove}
    onpointerup={onPointerUp}
    onpointercancel={onPointerCancel}
    onclick={onButtonClick}
  >
    {@render art()}
  </button>
{:else}
  <!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
  <span
    bind:this={root}
    class="mascot"
    class:mascot--decorative={reactive}
    class:mascot--bob={bob}
    class:mascot--roll={roll}
    class:mascot--blink={blinking}
    aria-hidden="true"
    onclick={reactive ? onDecorativeClick : undefined}
  >
    {@render art()}
  </span>
{/if}

<style>
  .mascot {
    display: inline-block;
    flex: none;
    border: 0;
    border-radius: 50%;
    padding: 0;
    background: none;
    color: inherit;
    line-height: 0;
    -webkit-tap-highlight-color: transparent;
  }

  .mascot-art {
    display: block;
    overflow: visible;
    filter: drop-shadow(0 6px 10px rgb(63 20 80 / 18%));
    transition: opacity 120ms ease;
  }

  .mascot--live {
    cursor: pointer;
    user-select: none;
    -webkit-user-select: none;
    /* Vertical swipes still scroll the page; horizontal ones drag the ball. */
    touch-action: pan-y;
    transition: transform 200ms cubic-bezier(0.3, 1.4, 0.5, 1);
  }

  .mascot--live:focus-visible {
    outline: 3px solid var(--accent-strong, currentColor);
    outline-offset: 3px;
  }

  @media (hover: hover) and (pointer: fine) {
    .mascot--live:hover {
      transform: rotate(-5deg) translateY(-2px);
    }
  }

  .mascot--live:active {
    transform: scale(0.94);
  }

  .mascot--bob .mascot-art {
    animation: mascot-bob 1800ms ease-in-out 3 alternate;
    transform-origin: 50% 90%;
  }

  .mascot--roll .mascot-art {
    animation: mascot-roll 1400ms linear infinite;
  }

  .mascot-particle {
    transform-box: fill-box;
    transform-origin: center;
    animation: mascot-float 1100ms ease-out var(--delay, 0ms) both;
  }

  .mascot--blink .mascot-art {
    opacity: 0.55;
  }

  @keyframes mascot-bob {
    from {
      transform: rotate(-6deg) translateY(0);
    }
    to {
      transform: rotate(6deg) translateY(-4px);
    }
  }

  @keyframes mascot-roll {
    to {
      transform: rotate(1turn);
    }
  }

  @keyframes mascot-float {
    0% {
      opacity: 0;
      transform: translate(0, 0) scale(0.4);
    }
    15% {
      opacity: 1;
    }
    100% {
      opacity: 0;
      transform: translate(var(--dx), var(--dy)) scale(1);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .mascot--bob .mascot-art,
    .mascot--roll .mascot-art {
      animation: none;
    }

    .mascot--live,
    .mascot--live:hover,
    .mascot--live:active {
      transform: none;
      transition: none;
    }

    .mascot-particle {
      display: none;
    }
  }
</style>
