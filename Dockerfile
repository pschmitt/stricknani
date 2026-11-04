FROM docker.io/nixos/nix:2.35.4 AS build

WORKDIR /src

COPY flake.nix flake.lock ./
COPY nix/ ./nix/
COPY rust/ ./rust/
COPY web/ ./web/

RUN nix --extra-experimental-features "nix-command flakes" build \
      --no-link \
      --print-out-paths \
      '.#stricknani' \
      > /tmp/stricknani-package

RUN <<'SH'
set -eu
package="$(cat /tmp/stricknani-package)"
mkdir -p /closure/nix/store
nix-store -qR "$package" > /tmp/stricknani-closure
while IFS= read -r store_path
do
  cp -a "$store_path" /closure/nix/store/
done < /tmp/stricknani-closure
mkdir -p /closure/bin /closure/tmp /closure/var/lib/stricknani
ln -s "$package/bin/stricknani-server" /closure/bin/stricknani-server
chmod 1777 /closure/tmp
chown 65532:65532 /closure/var/lib/stricknani
SH

FROM scratch

COPY --from=build /closure/ /

WORKDIR /var/lib/stricknani
USER 65532:65532
ENV BIND_HOST=0.0.0.0 \
    BIND_PORT=7674 \
    RUST_DATABASE_URL=sqlite:/var/lib/stricknani/stricknani.sqlite3 \
    RUST_MEDIA_ROOT=/var/lib/stricknani/media \
    TRUSTED_HOSTS=localhost,127.0.0.1 \
    SESSION_COOKIE_SECURE=true \
    RUST_SIGNUP_ENABLED=false \
    RUST_EXTERNAL_IMPORTS_ENABLED=false \
    RUST_AI_ENABLED=false \
    RUST_OCR_ENABLED=false \
    RUST_WAYBACK_ENABLED=false

EXPOSE 7674
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["/bin/stricknani-server", "health-check"]
CMD ["/bin/stricknani-server"]
