# Branding

The Stricknani mark is "Winky Pom": a blue yarn ball with two wraps, one open eye, one wink,
an open smile and rosy cheeks, tilted -8 degrees, on the launcher blue (`#8cb8ff`).

| File | Use |
| --- | --- |
| `icon.svg` | The launcher icon as one picture: the ball filling a circle on the launcher blue (`#8cb8ff`), no padding. Favicon, PWA icons, legacy launcher PNGs. |
| `icon-square.svg` | The same ball on a full-bleed blue square. Maskable PWA icon and Play icon (the platform applies its mask). |
| `icon-foreground.svg` | Ball and face on transparent, in the 108 unit adaptive-icon canvas, scaled 1.36x so the ball fills the visible launcher circle (no padding; the launcher background is the ball's blue). |
| `icon-monochrome.svg` | Single-colour ball with face and wraps cut out (Android themed icon). |
| `mascot.svg` | The ball cropped to its bounds, for the web `Mascot` component. |
| `playstore/` | `icon-512.png` and `feature-graphic.png` for the Play listing. |

## Regenerating derived assets

The masters are hand-maintained SVGs. Everything else is derived from them with

```sh
just branding
```

which runs `branding/build.py` (resvg and shapely come from nix). It rewrites:

- `web/static/favicon.svg`, `web/static/mascot.svg`, `web/static/icons/icon-{192,512,maskable-512}.png`
  (the PWA icons are the Android launcher icon: `icon-foreground.svg` on the launcher blue)
- `rust/static/favicon.svg`, `rust/static/icons/icon-{192,512}.png`
- `branding/playstore/icon-512.png` and `feature-graphic.png`
- `android/app/src/main/res/mipmap-*dpi/ic_launcher{,_round}.png`
- `android/app/src/main/res/drawable/ic_launcher_monochrome.xml` and `ic_stat_stricknani.xml`
  (the notification small icon, flat white silhouette with the wink cut out)

`ic_launcher_foreground.xml` and `ic_launcher_background.xml` are hand-translated from
`icon-foreground.svg`; keep them in sync if the artwork changes. The web `Mascot.svelte` and the
Android `Mascot` composable draw the same shapes in code so the face can animate.
