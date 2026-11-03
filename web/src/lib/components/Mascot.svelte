<script lang="ts">
  import { asset } from "$app/paths";

  // The yarn-ball mascot. `bob` adds a short, gentle sway (a greeting moment, not a
  // permanent animation); `roll` turns it like a ball of yarn while something loads.
  let {
    size = 96,
    bob = false,
    roll = false,
  }: { size?: number; bob?: boolean; roll?: boolean } = $props();
</script>

<span
  class="mascot"
  class:mascot--bob={bob}
  class:mascot--roll={roll}
  data-size={size}
  aria-hidden="true"
>
  <img src={asset("favicon.svg")} alt="" draggable="false" />
</span>

<style>
  .mascot {
    --mascot-size: 6rem;
    display: inline-block;
    width: var(--mascot-size);
    height: var(--mascot-size);
    flex: none;
  }

  .mascot[data-size="48"] {
    --mascot-size: 3rem;
  }
  .mascot[data-size="72"] {
    --mascot-size: 4.5rem;
  }
  .mascot[data-size="88"] {
    --mascot-size: 5.5rem;
  }
  .mascot[data-size="120"] {
    --mascot-size: 7.5rem;
  }

  .mascot img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: contain;
    filter: drop-shadow(0 6px 10px rgb(63 20 80 / 18%));
  }

  .mascot--bob img {
    animation: mascot-bob 1800ms ease-in-out 3 alternate;
    transform-origin: 50% 90%;
  }

  .mascot--roll img {
    animation: mascot-roll 1400ms linear infinite;
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

  @media (prefers-reduced-motion: reduce) {
    .mascot--bob img,
    .mascot--roll img {
      animation: none;
    }
  }
</style>
