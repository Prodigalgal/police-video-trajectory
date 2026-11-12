#!/usr/bin/env bash
set -Eeuo pipefail

: "${APP_VERSION:?APP_VERSION is required}"
: "${SOURCE_SHA:?SOURCE_SHA is required}"
: "${IMAGE_TAG:?IMAGE_TAG is required}"
: "${RELEASE_RUN_ID:?RELEASE_RUN_ID is required}"
: "${REGISTRY_NAMESPACE:?REGISTRY_NAMESPACE is required}"
: "${EXPECTED_CORE_DIGEST:?EXPECTED_CORE_DIGEST is required}"
: "${EXPECTED_RUNTIME_DIGEST:?EXPECTED_RUNTIME_DIGEST is required}"
: "${OUT_DIR:?OUT_DIR is required}"

[[ "$APP_VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
  echo "Invalid application version." >&2
  exit 2
}
[[ "$SOURCE_SHA" =~ ^[a-f0-9]{40}$ ]] || { echo "Invalid source SHA." >&2; exit 2; }
[[ "$RELEASE_RUN_ID" =~ ^[0-9]+$ ]] || { echo "Invalid release run ID." >&2; exit 2; }
[[ "$REGISTRY_NAMESPACE" =~ ^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*$ ]] || {
  echo "Invalid Docker Hub namespace." >&2
  exit 2
}
[[ "$EXPECTED_CORE_DIGEST" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Invalid Core digest." >&2; exit 2; }
[[ "$EXPECTED_RUNTIME_DIGEST" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo "Invalid Runtime digest." >&2; exit 2; }
[[ "$(tr -d '\r\n' < VERSION)" == "$APP_VERSION" ]] || {
  echo "Checked out VERSION does not match the release version." >&2
  exit 2
}
[[ "$IMAGE_TAG" == *"-v$APP_VERSION-ci-${SOURCE_SHA:0:12}" ]] || {
  echo "Image tag does not identify this source version and commit." >&2
  exit 2
}

mkdir -m 0755 -p "$OUT_DIR"
core_image="$REGISTRY_NAMESPACE/police-video-core:$IMAGE_TAG"
runtime_image="$REGISTRY_NAMESPACE/police-video-runtime:$IMAGE_TAG"

docker pull --platform linux/arm64 "$core_image@$EXPECTED_CORE_DIGEST"
docker pull --platform linux/arm64 "$runtime_image@$EXPECTED_RUNTIME_DIGEST"

core_image_id="$(docker image inspect --format '{{.Id}}' "$core_image@$EXPECTED_CORE_DIGEST")"
runtime_image_id="$(docker image inspect --format '{{.Id}}' "$runtime_image@$EXPECTED_RUNTIME_DIGEST")"
docker image tag "$core_image_id" "$core_image"
docker image tag "$runtime_image_id" "$runtime_image"

for image in "$core_image" "$runtime_image"; do
  platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")"
  [[ "$platform" == "linux/arm64" ]] || {
    echo "Image platform mismatch for $image: $platform" >&2
    exit 2
  }
done

core_digest="$EXPECTED_CORE_DIGEST"
runtime_digest="$EXPECTED_RUNTIME_DIGEST"

docker image save --output "$OUT_DIR/images.tar" "$core_image" "$runtime_image"
tar -tf "$OUT_DIR/images.tar" > /dev/null

jq -n \
  --arg app_version "$APP_VERSION" \
  --arg source_sha "$SOURCE_SHA" \
  --arg release_run_id "$RELEASE_RUN_ID" \
  --arg image_tag "$IMAGE_TAG" \
  --arg platform "linux/arm64" \
  --arg core_reference "$core_image" \
  --arg core_digest "$core_digest" \
  --arg runtime_reference "$runtime_image" \
  --arg runtime_digest "$runtime_digest" \
  '{
    schema_version: "offline-app-images-v1",
    application_version: $app_version,
    source_commit: $source_sha,
    github_actions_run_id: $release_run_id,
    image_tag: $image_tag,
    platform: $platform,
    images: [
      {name: "core", reference: $core_reference, registry_digest: $core_digest},
      {name: "runtime", reference: $runtime_reference, registry_digest: $runtime_digest}
    ]
  }' > "$OUT_DIR/manifest.json"

cp ci/import-offline-images.sh "$OUT_DIR/import-offline-images.sh"
chmod 0755 "$OUT_DIR/import-offline-images.sh"
cp ci/verify-offline-image-package.ps1 "$OUT_DIR/verify-offline-image-package.ps1"
cat > "$OUT_DIR/README.txt" <<EOF
Police Video Trajectory offline ARM64 application images.
Version: $APP_VERSION
Tag: $IMAGE_TAG
Source commit: $SOURCE_SHA
Successful release run: $RELEASE_RUN_ID
Platform: linux/arm64

Verify this package on Windows with verify-offline-image-package.ps1.
Copy the extracted package directory to each ARM64 Kubernetes node, then run:
  sudo bash import-offline-images.sh /path/to/offline-bundle
The Kubernetes workload manifests must use these exact image references with imagePullPolicy: IfNotPresent.
EOF
(
  cd "$OUT_DIR"
  sha256sum images.tar manifest.json import-offline-images.sh verify-offline-image-package.ps1 README.txt > SHA256SUMS.txt
  sha256sum --check SHA256SUMS.txt
)

{
  echo "Offline image package created."
  echo
  echo "- Version: $APP_VERSION"
  echo "- Image tag: $IMAGE_TAG"
  echo "- Source commit: $SOURCE_SHA"
  echo "- Core: $core_image@$core_digest"
  echo "- Runtime: $runtime_image@$runtime_digest"
  echo "- Package SHA-256: $(sha256sum "$OUT_DIR/images.tar" | cut -d' ' -f1)"
} >> "$GITHUB_STEP_SUMMARY"
