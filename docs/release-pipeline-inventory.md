# Release pipeline inventory

This inventory describes the public repository at `origin/main` commit
`bd99b5badb44c63a774acf6255e9e5bcf5ff96d0` (2026-11-03). The worktree's
current branch has unrelated local changes; this review used `git show
origin/main:<path>` for source evidence. The visible local tags are `v0.1.0`
and the rolling `latest`; no fetch or tag mutation was performed. `origin/main`
currently resolves to the v0.1.0 commit. `v0.1.0` is an annotated tag object
(`7db91d8...`) peeled to that same commit; `latest` points directly to the
commit, according to `git ls-remote`.

## Current release triggers and outputs

| Pipeline | Trigger and version rule | Published output | Production effect |
| --- | --- | --- | --- |
| GitHub APK release ([release.yaml](../.github/workflows/release.yaml#L19)) | Every push to `main`, or any `v*` / `*.*.*` tag; also manual dispatch. `main` moves the force-updated `latest` tag and prerelease. A tag publishes a permanent GitHub release. | APK workflow runs unit tests, builds signed APKs for four ABIs, embeds commit and version name, writes `SHA256SUMS`, and attests provenance. `latest` includes debug and release APKs; version tags include release APKs. | No container build or Play upload is performed here. Tag pushes are not constrained to a clean semantic version even though comments describe semver tags. |
| Play Store ([play-store.yaml](../.github/workflows/play-store.yaml#L18)) | Same version-tag patterns, or manual dispatch. Version name is normalized from a semantic tag; Play version code is `major * 1,000,000 + minor * 1,000 + patch`. Tag builds default to `internal,alpha` and completed status. | Validates Play listing metadata and committed assets; calls shared `play-store.yaml@main` to build/sign/publish an AAB. The caller passes app ID `blue.anika.wolle`, Gradle project `android/`, explicit version code/name, and inherited secrets. | Tag pushes publish to internal and alpha when the required publish variable and service-account secret are present. Production requires a manual dispatch and exact confirmation string; it is not the tag default. |
| Container ([container.yaml](../.github/workflows/container.yaml#L3)) | Push to `main` or PR targeting `main`, filtered to the Python app, Dockerfile, dependency metadata, Babel config, or this workflow. No tag trigger. | Builds and pushes GHCR tags `latest` (default branch only), branch, PR, and commit SHA; then starts the just-built image and checks `/healthz`. | A version tag does not cause this workflow to run. The image is the legacy Python service: Dockerfile installs the Python package and runs `uvicorn stricknani.main:app`. |
| Nix package ([package.nix](../nix/package.nix#L48), [pyproject.toml](../pyproject.toml#L1)) | Built as part of the flake package, not by a release tag workflow. | Both the Nix derivation and Python project metadata currently declare `0.1.0`. | This is the legacy Python package. No immutable release version is selected by a tag. |

### Android version details

The Gradle config defaults `versionCode` to `1` and `versionName` to `0.1.0`;
the APK workflow passes a version name derived from `git describe`, but does not
pass a version code. The Play workflow computes and passes both values. Thus APK
and Play artifacts for the same tag currently have a matching version name, but
different version-code policies. At `v0.1.0`, Play's deterministic code is
`1000`; APKs use Gradle's default `1`. See [Gradle config](../android/app/build.gradle.kts#L14)
and the [Play resolver](../.github/workflows/play-store.yaml#L80).

Both workflows accept prefixed and unprefixed semantic tags (`v1.2.3` and
`1.2.3`). The APK workflow treats any tag selected by its broad `v*` filter as
a permanent release; the Play version resolver rejects non-semver tags unless
explicit override inputs are provided (which tag pushes do not have). The
workflow run/ref should therefore be checked before calling an arbitrary tag a
complete multi-channel release.

## Current release state and guardrails

- Visible Git history has `v0.1.0` and `latest`. `latest` is intentionally
  mutable: `.github/workflows/release.yaml` uses `git tag -f` and a force push
  for main builds (lines 257–262). Treat it as a moving preview pointer, never
  as a rollback reference. Do not force-move semantic version tags.
- The existing GitHub release pipeline and Play Store tag run for legacy
  `v0.1.0` are already being handled by another operator. Do not duplicate or
  restart that release work.
- Anonymous GHCR inspection of
  `ghcr.io/pschmitt/stricknani:sha-bd99b5b` returned `manifest unknown`; lookup
  using the full commit hash as the suffix also returned `manifest unknown`.
  There is no verified immutable image reference from this check. This may mean
  no such tag exists or that the package is not anonymously readable. Resolve
  the actual image and digest from a successful, authorized registry lookup
  before relying on it for rollback.
- The APK release job grants `contents`, `actions`, `id-token`, and
  `attestations` write permissions to its single job. The Play caller has
  `contents: read`; its upload permission/secrets are handled by the shared
  reusable workflow and inherited secrets. The container job grants
  `contents: read` and `packages: write`.

## Desired release boundary

The planned final Rust/web/Android `v1.0.0` release is separate from today's
legacy Python `v0.1.0` release. Cut `v1.0.0` only after the merged `main`
revision has passed production cutover verification. At that point, verify the
same source revision and version across the Rust/web package, Android APKs,
Play AAB, and the container deployment. Confirm each published artifact's
checksums/digest and workflow result before treating the tag as complete.

Before enabling a final-version container publication, decide whether the
container still represents the legacy Python service or the new Rust/web
service. Today's container build is Python-only and excludes the Rust/web and
Android paths from its triggers. A tag alone currently publishes no container.
If the new service needs an image, add an explicit tag-based image build and
health verification, with a commit-derived tag and immutable registry digest;
do not infer that it exists from the Android release.

## Safe legacy rollback plan

Keep rollback references independent of mutable names:

1. **Nix:** before changing the production package, record the deployed system
   generation and its exact store closure/package derivation. Keep that
   generation rooted and available through the cutover verification window.
   Roll back by switching to the recorded prior system generation (or by
   rebuilding the exact prior pinned source/flake inputs), then verify the
   service and health endpoint. Do not rely on the current Nix package's
   hard-coded `0.1.0` as a source revision identifier; the version is not a
   content pin.
2. **Container:** first obtain the image digest for the image actually running
   in production and retain that image. Record both its source commit and the
   registry reference `ghcr.io/...@sha256:<digest>`. Configure rollback to
   that digest, not `latest`, a branch tag, or an unverified `sha-*` tag. Test
   the rollback image's health check before cutover. No digest was verified in
   this inventory, so this prerequisite remains open.
3. **Cutover record:** capture the previous Nix generation/closure and image
   digest beside the candidate revision, test receipt, and cutover time. Keep
   the legacy Python service and its state intact until the new production
   revision is verified. If rollback is needed, restore the matching legacy
   package/image first and verify service health before resuming writes.

## Post-cutover tag consistency checks

For the eventual `v1.0.0` release, confirm all of the following against the
same merged `main` commit:

- `v1.0.0` is a new immutable tag; no existing version tag is moved.
- Package metadata and Nix package version reflect the Rust/web release;
  legacy Python version metadata is changed only if that package remains part
  of the release.
- Android APK `versionName`, Play AAB `versionName`, and release notes say
  `1.0.0`; Android `versionCode` values are valid and increase beyond the
  last Play upload. Keep APK and Play version-code behavior deliberately
  aligned or document the difference.
- GitHub release APK assets have SHA256 checksums and provenance attestation;
  Play workflow is green and uploads the intended internal/alpha tracks.
- The container workflow ran for the Rust/web source, its published digest
  corresponds to the same commit, and its health verification passed.
- The deployment points to immutable Nix and container references. Save the
  prior Nix generation and prior container digest before switching production.

## Evidence and validation

Workflow and version claims above come from the linked files at `origin/main`
`bd99b5badb44c63a774acf6255e9e5bcf5ff96d0`. The current worktree has unrelated
user changes that were left untouched. No builds or CI runs were started. The
requested documentation-only whitespace check is `git diff --check`.
