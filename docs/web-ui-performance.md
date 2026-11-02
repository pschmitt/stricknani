# Web UI performance lab report

## Method

The measured frontend is the deployed 522 package. Its web root is
`/nix/store/v2dd2d6jjn2v168flw2k1yr8xvq1pvmx-stricknani-web-0.1.0`; the matching
raw server is
`/nix/store/z1v66gbfzm2ax4y4n8cjmnkr347cpsdf-stricknani-server-0.1.0/bin/.stricknani-server-wrapped`.
The package's `200.html`, `messages.json`, and `app-sw.js` SHA-256 values are
`bf63f41919e0a6426e000569170ca5c66f6654492476a6f14250ccdee8d98056`,
`f5b67b45f4935e871846bca290a2b6d01665cb63cdd740bf137eb69650f4b84a`, and
`5feb0521c818a2ff9a5c6c13b7612cf94de49356828b96d1845b60eb4ec3aa31`.
The runner SHA-256 is
`244cd95649ab8d26c872c00a4ac714bf19e19bf5b552a989456d4810f14040b4`.

Five samples ran in Chromium 156.0.8078.73 headless mobile emulation at 390 ×
844 CSS pixels, device scale factor 1, CPU throttling 4×, and browser network
throttling at 150 ms latency, 1.6 Mbps down and 750 kbps up. Each sample used a
fresh browser context and one full document session against a disposable
synthetic account. The runner asserted that the document's
`performance.timeOrigin` did not change during the measured journey.

The journey searched for a non-matching project, cleared the filter, opened the
seeded project, moved to the next and previous pattern steps, increased and
restored reading text size, opened and dismissed the photo viewer, entered a
project name in the editor, undid the change, and returned to the reader. Each
action used Playwright's normal input APIs, and the runner checked the resulting
UI state. Event collection continued for the entire document session; separate
before-and-after snapshots measured search and reader-open actions without
clearing the shared observer.

Event Timing entries were observed with a 16 ms `durationThreshold` and grouped
by `interactionId`. A gesture's latency is the longest `duration` among its
entries. The lab candidate uses the browser's native document
`performance.interactionCount` and the p98 candidate index used by Google's
`web-vitals` InteractionManager, retaining the ten slowest interactions. The
interaction count and candidate-index method follow the
[web-vitals InteractionManager](https://github.com/GoogleChrome/web-vitals/blob/main/src/lib/InteractionManager.ts)
and its
[interaction-count polyfill](https://github.com/GoogleChrome/web-vitals/blob/main/src/lib/polyfills/interactionCountPolyfill.ts).
The Event Timing observer uses the [W3C Event Timing API](https://www.w3.org/TR/event-timing/).
INP's interaction grouping and 200 ms good threshold are described in the
[web.dev INP guide](https://web.dev/articles/inp).

The harness reports the native document interaction count, observed groups,
coverage, event-entry count, event-type counts, and duration buckets. A lab
candidate is assessable only when at least eight interaction groups are
observed and those groups cover at least half the native document count. This
does not convert unobserved events to zero; with the 16 ms observer threshold,
shorter interactions are absent from the Event Timing sample.

The runner computes gzip level 6 sizes from loaded JavaScript and CSS response
bodies. Those estimates are compared with the 150 KiB JavaScript and 50 KiB CSS
budgets. A local standard-library proxy preserves browser Host, Origin, and
Cookie headers and gzips eligible content types over 1 KiB. Chromium Resource
Timing separately reports encoded body bytes and transfer bytes, which include
response overhead. In this run, the JavaScript and CSS resources included both
gzip and identity responses, while the catalog response was gzip encoded.

## Results

| Sample | LCP (ms) | CLS | JS gzip-6 (B) | CSS gzip-6 (B) | Catalog gzip-6 (B) | Cold route ready (ms) | Search ready (ms) | Reader ready (ms) | Search event (ms) | Reader event (ms) | Lab candidate (ms) | Native interactions | Observed groups | Coverage | Event entries |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 2232 | 0.0000 | 65067 | 7257 | 22876 | 1950.8 | 364.0 | 643.0 | 24 | 32 | 40 | 37 | 25 | 67.6% | 66 |
| 2 | 2208 | 0.0000 | 65067 | 7257 | 22876 | 1925.3 | 408.7 | 645.7 | 24 | 32 | 40 | 37 | 29 | 78.4% | 72 |
| 3 | 2216 | 0.0000 | 65067 | 7257 | 22876 | 1941.5 | 352.1 | 657.4 | 32 | 24 | 40 | 37 | 33 | 89.2% | 89 |
| 4 | 2220 | 0.0000 | 65067 | 7257 | 22876 | 1943.4 | 382.0 | 662.8 | 24 | 40 | 40 | 37 | 28 | 75.7% | 69 |
| 5 | 2236 | 0.0000 | 65067 | 7257 | 22876 | 1943.1 | 385.0 | 646.8 | 32 | 32 | 40 | 37 | 34 | 91.9% | 90 |
| Median | 2220 | 0.0000 | 65067 | 7257 | 22876 | 1943.1 | 382.0 | 646.8 | 24 | 32 | 40 | 37 | 29 | 78.4% | 72 |

The native interaction count was available in all samples, as were Event Timing
and first-input observers. Each document had 37 interactions; 25–34 groups had
at least one Event Timing entry at the 16 ms threshold, with 66–90 entries per
sample. Candidate index was 0 each time because `floor(37 / 50) = 0`, so the
candidate is the slowest observed interaction. It was 40 ms in every sample.
The observed-interaction coverage ranged from 67.6% to 91.9%. Search action
latency was 24–32 ms and reader-open latency was 24–40 ms. Each sample loaded
32 resources: 24 JavaScript, 7 CSS, and one catalog resource.

The gzip-6 estimates met the configured budgets in every sample: 65,067 B of
JavaScript against 153,600 B, and 7,257 B of CSS against 51,200 B. In
Resource Timing, JavaScript encoded bytes were 65,815 B and transfer bytes
were 73,015 B; CSS encoded bytes were 7,854 B and transfer bytes were 9,954 B;
the catalog was 22,876 B encoded and 23,176 B transferred. These transport
measurements are distinct from the gzip-6 budget estimates. LCP ranged from
2,208 to 2,236 ms, and CLS was 0 in all five samples.

The terminal run exited 0. Its mode-0600 sanitized report and exit receipt are
retained in the private `stricknani-contrast-performance-522-20261102` run
directory.

## Acceptance and limits

- **First-route JavaScript:** pass. Median 65,067 B gzip-6, below 153,600 B
  (150 KiB).
- **First-route CSS:** pass. Median 7,257 B gzip-6, below 51,200 B (50 KiB).
- **LCP:** pass. Median 2,220 ms, below the 2,500 ms lab target.
- **CLS:** pass in this fixture. Median 0, below the 0.1 target.
- **Scripted-session lab INP candidate:** pass. Median and maximum were 40 ms,
  below the 200 ms lab target, with 67.6–91.9% observed interaction coverage.

This is a controlled scripted lab session with synthetic data and mobile
emulation, not a physical handset, real mobile network, human study, field
population estimate, or production p75. Event Timing omits interactions below
the 16 ms threshold. The lab candidate is based on the observed groups in this
scripted document session; it is not a field INP measurement. The shared host
had moderate background load during the run, so the results are not an
idle-host comparison.

## Reproduction

Run from the source checkout with an existing Playwright environment and its
matching browser cache. Keep the synthetic fixture directory new and outside
the source tree:

```sh
python3 -m ci.stricknani.web_performance_browser \
  --binary <frozen-rust-server> \
  --web-root <frozen-web-root> \
  --private-dir <new-private-run-directory>
```

The runner creates and removes its synthetic fixture data; retain only the
sanitized JSON report and exit status as run evidence.
