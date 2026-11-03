import {
  dismissParticles,
  dragLook,
  lockAxis,
  swipeOutcome,
  type Axis,
} from "./swipe-dismiss.ts";

/** The playful spring the web uses elsewhere (overshoots, then settles). */
const SPRING = "cubic-bezier(0.3, 1.6, 0.5, 1)";
const HEART_PATH =
  "M20.8 8.8c0 5.7-8.8 10.3-8.8 10.3S3.2 14.5 3.2 8.8a4.6 4.6 0 0 1 8.8-2.1 4.6 4.6 0 0 1 8.8 2.1Z";

export type DismissableOptions = {
  /** Runs as the exit begins: remember the dismissal here. */
  onstart?: () => void;
  /** Runs once the card has left and the space has collapsed. */
  ondone: () => void;
};

export type Dismissable = {
  /** Play the exit (tap on the close button). Ignored while already leaving. */
  dismiss: (direction?: 1 | -1) => void;
  destroy: () => void;
};

const prefersReducedMotion = (): boolean =>
  typeof window !== "undefined" &&
  window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function puff(rect: DOMRect): void {
  const layer = document.createElement("div");
  layer.setAttribute("aria-hidden", "true");
  Object.assign(layer.style, {
    position: "fixed",
    left: `${rect.left + rect.width / 2}px`,
    top: `${rect.top + rect.height / 2}px`,
    width: "0",
    height: "0",
    pointerEvents: "none",
    zIndex: "60",
  });
  for (const p of dismissParticles(Math.random)) {
    const bit = document.createElement("span");
    bit.style.cssText = `position:absolute;left:${-p.size / 2}px;top:${-p.size / 2}px;width:${p.size}px;height:${p.size}px;`;
    if (p.kind === "heart") {
      bit.innerHTML = `<svg viewBox="0 0 24 24" width="${p.size * 1.6}" height="${p.size * 1.6}" style="margin:${-p.size * 0.3}px 0 0 ${-p.size * 0.3}px"><path d="${HEART_PATH}" fill="${p.color}"/></svg>`;
    } else {
      bit.style.borderRadius = "50%";
      bit.style.background = p.color;
    }
    layer.append(bit);
    bit.animate(
      [
        { opacity: 0, transform: "translate(0,0) scale(0.3)" },
        { opacity: 1, offset: 0.2 },
        {
          opacity: 0,
          transform: `translate(${p.dx}px, ${p.dy}px) scale(1) rotate(${p.dx}deg)`,
        },
      ],
      { duration: 800, delay: p.delay, easing: "ease-out", fill: "both" },
    );
  }
  document.body.append(layer);
  window.setTimeout(() => layer.remove(), 1100);
}

/**
 * Makes a card dismissible: swipe it sideways (Pointer Events, vertical scrolling stays with the
 * browser via `touch-action: pan-y`) or call `dismiss()`. Leaving is a squish, a tilt and a flight
 * off the side with a puff of yarn, then the space collapses so the content below glides up.
 */
export function createDismissable(
  node: HTMLElement,
  options: DismissableOptions,
): Dismissable {
  let leaving = false;
  let pointerId: number | undefined;
  let startX = 0;
  let startY = 0;
  let dx = 0;
  let axis: Axis;
  let samples: { x: number; t: number }[] = [];
  let spring: Animation | undefined;

  const transformFor = (x: number, width: number): string => {
    const look = dragLook(x, width);
    return `translateX(${x}px) rotate(${look.rotate}deg)`;
  };

  function release(): void {
    if (pointerId !== undefined && node.hasPointerCapture(pointerId)) {
      node.releasePointerCapture(pointerId);
    }
    pointerId = undefined;
    axis = undefined;
    node.style.userSelect = "";
  }

  function springBack(from: number, width: number): void {
    const look = dragLook(from, width);
    spring = node.animate(
      [
        {
          transform: transformFor(from, width),
          opacity: String(look.opacity),
        },
        { transform: "none", opacity: "1" },
      ],
      {
        duration: prefersReducedMotion() ? 120 : 520,
        easing: prefersReducedMotion() ? "ease-out" : SPRING,
      },
    );
    node.style.transform = "";
    node.style.opacity = "";
  }

  async function leave(direction: 1 | -1, from: number): Promise<void> {
    if (leaving) return;
    leaving = true;
    spring?.cancel();
    options.onstart?.();
    const reduced = prefersReducedMotion();
    const rect = node.getBoundingClientRect();
    const width = rect.width;
    const startLook = dragLook(from, width);
    node.style.pointerEvents = "none";
    node.style.transform = "";
    node.style.opacity = "";

    const exit = reduced
      ? node.animate(
          [
            { transform: `translateX(${from}px)`, opacity: startLook.opacity },
            { transform: `translateX(${from}px)`, opacity: 0 },
          ],
          { duration: 140, easing: "ease-out", fill: "forwards" },
        )
      : node.animate(
          [
            {
              transform: transformFor(from, width),
              opacity: startLook.opacity,
            },
            {
              // Anticipation: squish and tilt the opposite way, then fling.
              offset: 0.28,
              transform: `translateX(${from - direction * 10}px) scale(1.05, 0.93) rotate(${-direction * 3}deg)`,
              opacity: 1,
            },
            {
              transform: `translateX(${direction * width * 1.3}px) scale(0.9) rotate(${direction * 14}deg)`,
              opacity: 0,
            },
          ],
          { duration: 520, easing: SPRING, fill: "forwards" },
        );
    if (!reduced) puff(rect);
    await exit.finished.catch(() => undefined);

    // Collapse the room the card took, row gap included, so what follows glides up.
    const gap = parseFloat(getComputedStyle(node.parentElement ?? node).rowGap);
    const margin = Number.isFinite(gap) ? gap : 0;
    const style = getComputedStyle(node);
    const pad = `${style.paddingTop} ${style.paddingRight} ${style.paddingBottom} ${style.paddingLeft}`;
    node.style.overflow = "clip";
    const collapse = node.animate(
      [
        { height: `${rect.height}px`, marginTop: "0px", padding: pad },
        {
          height: "0px",
          marginTop: `${-margin}px`,
          padding: `0 ${style.paddingRight} 0 ${style.paddingLeft}`,
        },
      ],
      {
        duration: reduced ? 160 : 340,
        easing: reduced ? "ease-out" : "cubic-bezier(0.4, 0, 0.2, 1)",
        fill: "forwards",
      },
    );
    await collapse.finished.catch(() => undefined);
    node.hidden = true;
    options.ondone();
  }

  function onPointerDown(event: PointerEvent): void {
    if (leaving || pointerId !== undefined) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    pointerId = event.pointerId;
    startX = event.clientX;
    startY = event.clientY;
    dx = 0;
    axis = undefined;
    samples = [{ x: event.clientX, t: event.timeStamp }];
    spring?.cancel();
  }

  function onPointerMove(event: PointerEvent): void {
    if (event.pointerId !== pointerId || leaving) return;
    const mx = event.clientX - startX;
    const my = event.clientY - startY;
    if (axis === undefined) {
      axis = lockAxis(mx, my);
      if (axis === "horizontal") {
        node.setPointerCapture(event.pointerId);
        node.style.userSelect = "none";
        // Start from where the lock happened so the card does not jump.
        startX = event.clientX;
        samples = [{ x: event.clientX, t: event.timeStamp }];
        return;
      }
      if (axis === "vertical") {
        pointerId = undefined; // scrolling; the browser owns the gesture
      }
      return;
    }
    if (axis !== "horizontal") return;
    dx = event.clientX - startX;
    samples.push({ x: event.clientX, t: event.timeStamp });
    samples = samples.filter((s) => event.timeStamp - s.t < 100);
    const look = dragLook(dx, node.offsetWidth);
    node.style.transform = transformFor(dx, node.offsetWidth);
    node.style.opacity = String(look.opacity);
  }

  function onPointerUp(event: PointerEvent): void {
    if (event.pointerId !== pointerId) return;
    const wasSwipe = axis === "horizontal";
    const first = samples[0];
    const last = samples[samples.length - 1];
    const velocity =
      first && last && last.t > first.t
        ? (last.x - first.x) / (last.t - first.t)
        : 0;
    release();
    if (!wasSwipe) return;
    // The drag ends over whatever is under the finger; do not let it click.
    node.addEventListener("click", (e) => e.stopPropagation(), {
      capture: true,
      once: true,
    });
    const width = node.offsetWidth;
    const outcome = swipeOutcome({ dx, width, velocity });
    if (outcome.kind === "dismiss") void leave(outcome.direction, dx);
    else springBack(dx, width);
  }

  function onPointerCancel(event: PointerEvent): void {
    if (event.pointerId !== pointerId) return;
    const wasSwipe = axis === "horizontal";
    release();
    if (wasSwipe) springBack(dx, node.offsetWidth);
  }

  node.style.touchAction = "pan-y";
  node.addEventListener("pointerdown", onPointerDown);
  node.addEventListener("pointermove", onPointerMove);
  node.addEventListener("pointerup", onPointerUp);
  node.addEventListener("pointercancel", onPointerCancel);

  return {
    dismiss: (direction = 1) => void leave(direction, 0),
    destroy: () => {
      node.removeEventListener("pointerdown", onPointerDown);
      node.removeEventListener("pointermove", onPointerMove);
      node.removeEventListener("pointerup", onPointerUp);
      node.removeEventListener("pointercancel", onPointerCancel);
    },
  };
}
