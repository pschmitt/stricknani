<script lang="ts">
  // Decorative illustration for the Create page: a woven basket with three
  // yarn balls (project, yarn and import tones), crossed needles and a loose
  // strand. Colours come from the theme tokens, so it follows light and dark.
  let { size = 132 }: { size?: number } = $props();
</script>

<svg
  class="knitting-basket"
  width={size}
  height={size * 0.9}
  viewBox="0 0 160 144"
  aria-hidden="true"
  focusable="false"
>
  <!-- needles, behind the yarn -->
  <g stroke-linecap="round">
    <line x1="46" y1="70" x2="104" y2="8" class="needle" stroke-width="5" />
    <line x1="114" y1="70" x2="62" y2="6" class="needle" stroke-width="5" />
  </g>
  <circle cx="104" cy="8" r="6" class="needle-cap" />
  <circle cx="62" cy="6" r="6" class="needle-cap needle-cap--alt" />

  <!-- yarn balls -->
  <g>
    <circle cx="56" cy="74" r="22" class="ball ball--projects" />
    <path
      d="M38 64q18-8 36 2M36 78q20 10 40-2M46 54q-4 20 6 40"
      class="wrap wrap--projects"
    />
  </g>
  <g>
    <circle cx="104" cy="72" r="24" class="ball ball--yarns" />
    <path
      d="M84 62q20-10 40 0M82 78q22 12 44-2M96 50q10 22-2 46"
      class="wrap wrap--yarns"
    />
  </g>
  <g>
    <circle cx="80" cy="84" r="20" class="ball ball--create" />
    <path d="M62 78q18-8 36 2M64 92q16 8 32-2" class="wrap wrap--create" />
  </g>

  <!-- basket -->
  <path d="M22 92h116l-12 46H34z" class="basket" />
  <path d="M18 88h124a4 4 0 0 1 0 10H18a4 4 0 0 1 0-10z" class="basket-rim" />
  <g class="weave">
    <path d="M30 108h100M33 120h94M36 131h88" />
    <path d="M50 98l3 40M70 98l1 40M90 98l-1 40M110 98l-3 40" />
  </g>

  <!-- loose strand draped over the rim, in front of the basket -->
  <path d="M116 80c10 2 16 8 14 16s-8 10-6 18 10 8 12 2" class="strand" />

  <!-- sparkles -->
  <path d="M18 40l3 8 8 3-8 3-3 8-3-8-8-3 8-3z" class="sparkle" />
  <path
    d="M144 28l2 5 5 2-5 2-2 5-2-5-5-2 5-2z"
    class="sparkle sparkle--small"
  />
</svg>

<style>
  .knitting-basket {
    flex: none;
    overflow: visible;
  }

  .needle {
    stroke: var(--ink-muted);
  }

  .needle-cap {
    fill: var(--tone-create);
  }

  .needle-cap--alt {
    fill: var(--tone-projects);
  }

  .ball--projects {
    fill: var(--tone-projects-wash);
    stroke: var(--tone-projects);
    stroke-width: 3;
  }

  .ball--yarns {
    fill: var(--tone-yarns-wash);
    stroke: var(--tone-yarns);
    stroke-width: 3;
  }

  .ball--create {
    fill: var(--tone-create-wash);
    stroke: var(--tone-create);
    stroke-width: 3;
  }

  .wrap {
    fill: none;
    stroke-width: 2.5;
    stroke-linecap: round;
    opacity: 0.75;
  }

  .wrap--projects {
    stroke: var(--tone-projects);
  }

  .wrap--yarns {
    stroke: var(--tone-yarns);
  }

  .wrap--create {
    stroke: var(--tone-create);
  }

  .strand {
    fill: none;
    stroke: var(--tone-yarns);
    stroke-width: 3;
    stroke-linecap: round;
  }

  .basket {
    fill: color-mix(in srgb, var(--tone-gauge) 55%, var(--surface));
  }

  .basket-rim {
    fill: var(--tone-gauge);
  }

  .weave {
    fill: none;
    stroke: color-mix(in srgb, var(--tone-gauge) 70%, var(--ink));
    stroke-width: 2;
    stroke-linecap: round;
    opacity: 0.45;
  }

  .sparkle {
    fill: var(--tone-create);
  }

  .sparkle--small {
    fill: var(--tone-yarns);
  }
</style>
