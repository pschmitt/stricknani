# Web UI performance lab report

## Method

The measured frontend is the frozen combined static artifact built from source
commit `6a30d04944ed8b795832025254d7787b15687b45`. Its static tree SHA-256 is
`606edda18fbf15643baa5ce715d0ef91aa6b92e0d95603bf3c3a20461f871e94`, and its
manifest SHA-256 is
`d428ec1d61d57c671498fa26b201d0ae37f16c1ae97baeb2ac4cf30046bc1f64`. The
matching integrated Rust server executable SHA-256 is
`946188cc08f1be8d417b8a06484b0695232f74c64a0abf02ab7ba00f3c50b84e`.
The runner measured here has SHA-256
`dbaeb7a4635f8a409a7463bc6efe005e96af7d21ba3dff8dce48e7aeb3076fea`.

Five samples ran in Chromium 156 headless mobile emulation at 390 × 844 CSS
pixels, device scale factor 1, CPU throttling 4×, and browser network
throttling at 150 ms latency, 1.6 Mbps down and 750 kbps up. Each sample used
a fresh browser context and one full document session against a disposable
synthetic account. The runner asserted that the document's `performance.timeOrigin`
did not change during the measured journey.

The journey searched for a non-matching project, cleared the filter, opened the
seeded project, moved to the next and previous pattern steps, increased and
restored reading text size, opened and dismissed the photo viewer, entered a
project name in the editor, undid the change, and returned to the reader. Each
action was made through Playwright's normal trusted input APIs, and the runner
checked the resulting UI state. Event collection continued for the entire
document session; separate before-and-after snapshots measure search and
reader-open actions without clearing the shared observer.

Event Timing entries were observed with a 16 ms `durationThreshold` and grouped
by `interactionId`. A gesture's latency is the longest `duration` among its
entries. The lab candidate uses the browser's native document
`performance.interactionCount` and the p98 candidate index used by Google's
`web-vitals` InteractionManager, retaining the ten slowest interactions. The
interaction count and candidate-index method follow the
[web-vitals InteractionManager](https://github.com/GoogleChrome/web-vitals/blob/main/src/lib/InteractionManager.ts)
and its
[interaction-count polyfill](https://github.com/GoogleChrome/web-vitals/blob/main/src/lib/polyfills/interactionCountPolyfill.ts).
The Event Timing observer uses the
[W3C Event Timing API](https://www.w3.org/TR/event-timing/). INP's interaction
grouping and 200 ms good threshold are described in the
[web.dev INP guide](https://web.dev/articles/inp).

The harness reports the native document interaction count, observed groups,
coverage, event-entry count, event-type counts, and duration buckets. The lab
candidate is marked assessable only when at least eight interaction groups are
observed and those groups cover at least half the native document count. This
does not convert unobserved events to zero; with the 16 ms observer threshold,
shorter interactions are not present in the Event Timing sample.

The runner computes gzip level 6 sizes from loaded JavaScript and CSS response
bodies. A local standard-library proxy preserves browser Host, Origin and
Cookie headers and gzips eligible content types over 1 KiB. Chromium Resource
Timing reports encoded body and transfer bytes separately; small assets may
remain identity encoded, and transfer size includes response overhead.

## Results

| Sample | LCP (ms) | CLS | JS gzip-6 (B) | CSS gzip-6 (B) | Catalog gzip-6 (B) | Search ready (ms) | Reader ready (ms) | Lab INP candidate (ms) | Native interactions | Observed groups | Coverage | Event entries |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1924 | 0.0000 | 52732 | 6150 | 21247 | 350.6 | 651.5 | 32 | 37 | 34 | 91.9% | 89 |
| 2 | 1916 | 0.0000 | 52732 | 6150 | 21247 | 345.8 | 615.9 | 32 | 37 | 33 | 89.2% | 86 |
| 3 | 1908 | 0.0000 | 52732 | 6150 | 21247 | 321.1 | 622.9 | 32 | 37 | 33 | 89.2% | 89 |
| 4 | 1916 | 0.0000 | 52732 | 6150 | 21247 | 341.0 | 626.9 | 32 | 37 | 32 | 86.5% | 86 |
| 5 | 1900 | 0.0000 | 52732 | 6150 | 21247 | 360.3 | 614.4 | 32 | 37 | 26 | 70.3% | 69 |
| Median | 1916 | 0.0000 | 52732 | 6150 | 21247 | 345.8 | 622.9 | 32 | 37 | 33 | 89.2% | 86 |

The native count was available in every sample, and both Event Timing and
first-input observers were supported. All five candidates were assessable.
Each session had 37 document interactions; 26–34 groups had at least one
Event Timing entry at the 16 ms threshold. Candidate index was 0 in every
sample because `floor(37 / 50) = 0`, so the candidate is the slowest observed
interaction. The candidate was 32 ms in all samples. Observed interactions
fell in the 16–39 ms duration bucket; no measured group reached 100 ms. Across
the sessions, event-type interaction counts ranged from 9–10 clicks, 9–10
pointer-down/up events, 17–25 keydowns, 15–22 keypresses and 10–16 keyups.

Action-local Event Timing latency was 24–32 ms for search and 32 ms for
reader-open. LCP ranged from 1,900 to 1,924 ms. Each sample loaded 18 JavaScript
files, 3 CSS files and one catalog resource. All five passed the configured
asset budgets and the scripted 200 ms lab candidate target.

The second run had exit status 0. Its sanitized report and host metadata are
retained with the run artifacts. The shared test machine had 32 logical CPUs;
load average was 16.65 before and 16.99 after the run, so these timings include
moderate background load and are not an idle-host comparison.

## Acceptance and limits

- **First-route JavaScript:** pass. 52,732 B gzip-6, under 153,600 B (150 KiB).
- **First-route CSS:** pass. 6,150 B gzip-6, under 51,200 B (50 KiB).
- **LCP:** pass. Median 1,916 ms, under the 2,500 ms lab target.
- **CLS:** pass in this fixture. Median 0.0000, under the 0.1 target.
- **Scripted-session lab INP candidate:** pass. Median and maximum were 32 ms,
  under the 200 ms lab target, with 70.3–91.9% observed interaction coverage.

This is a controlled scripted lab session with synthetic data and mobile
emulation, not a physical handset, real mobile network, human study, field
population estimate, or production p75. Event Timing omits interactions below
the 16 ms threshold. It does not establish behavior across all device/browser
combinations or all user journeys. The shared host's background load is an
additional limitation.

## Reproduction

From the source checkout, run the focused harness with the frozen server and
webroot paths for the environment:

```sh
python3 -m ci.stricknani.web_performance_browser \
  --binary <frozen-rust-server> \
  --web-root <frozen-webroot> \
  --private-dir <new-private-run-directory>
```

The run directory must be new and outside the source checkout. The helper
creates and removes its synthetic fixture data there; retain only the
sanitized JSON report and exit status as run evidence.
