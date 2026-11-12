#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: sudo bash import-offline-images.sh <package-directory>" >&2
  exit 2
fi
[[ "$(id -u)" -eq 0 ]] || { echo "Run as root." >&2; exit 2; }
[[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]] || {
  echo "This package contains linux/arm64 images." >&2
  exit 2
}
command -v ctr > /dev/null || { echo "containerd ctr command not found." >&2; exit 2; }
command -v python3 > /dev/null || { echo "python3 is required to read manifest.json." >&2; exit 2; }

bundle_dir="$(realpath "$1")"
[[ -d "$bundle_dir" ]] || { echo "Package directory does not exist." >&2; exit 2; }
cd "$bundle_dir"
sha256sum --check SHA256SUMS.txt
tar -tf images.tar > /dev/null

references="$(python3 - <<'PY'
import json
import re
import tarfile

with open("manifest.json", encoding="utf-8") as source:
    manifest = json.load(source)
if manifest.get("schema_version") != "offline-app-images-v1":
    raise SystemExit("Unsupported offline image package schema.")
if manifest.get("platform") != "linux/arm64":
    raise SystemExit("Offline image package platform is not linux/arm64.")
version = manifest.get("application_version", "")
source_sha = manifest.get("source_commit", "")
image_tag = manifest.get("image_tag", "")
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
    raise SystemExit("Invalid application version.")
if not re.fullmatch(r"[a-f0-9]{40}", source_sha):
    raise SystemExit("Invalid source commit.")
if not re.fullmatch(rf"[0-9]{{8}}-v{re.escape(version)}-ci-{source_sha[:12]}", image_tag):
    raise SystemExit("Image tag does not match its version and source commit.")
images = manifest.get("images")
if not isinstance(images, list) or len(images) != 2:
    raise SystemExit("Expected exactly the Core and Runtime images.")
if {image.get("name") for image in images} != {"core", "runtime"}:
    raise SystemExit("Unexpected application image set.")
archive = tarfile.open("images.tar", "r:*")
try:
    archive_manifest = json.load(archive.extractfile("manifest.json"))
finally:
    archive.close()
archive_references = {
    reference
    for image in archive_manifest
    for reference in (image.get("RepoTags") or [])
}
expected_references = {image.get("reference") for image in images}
if archive_references != expected_references:
    raise SystemExit("Image references in images.tar do not match manifest.json.")
for image in images:
    if not isinstance(image.get("reference"), str) or not image["reference"]:
        raise SystemExit("An image reference is missing.")
    if not image["reference"].endswith(f":{image_tag}"):
        raise SystemExit("An image reference does not match the package tag.")
    if not re.fullmatch(r"sha256:[a-f0-9]{64}", image.get("registry_digest", "")):
        raise SystemExit("An immutable registry digest is missing.")
    print(image["reference"])
PY
)"

normalize_reference() {
  local reference="$1"
  local first_component="${reference%%/*}"
  if [[ "$reference" != */* ]]; then
    printf 'docker.io/library/%s\n' "$reference"
  elif [[ "$first_component" != *.* && "$first_component" != *:* && "$first_component" != "localhost" ]]; then
    printf 'docker.io/%s\n' "$reference"
  else
    printf '%s\n' "$reference"
  fi
}

# containerd 2.x reports digest-qualified references (name:tag@sha256:...), while
# 1.x reports bare tags. Strip the digest qualifier so tag comparison stays exact
# on both, and drop entries that are only a bare digest.
normalize_installed() {
  local line
  while IFS= read -r line; do
    line="${line%@sha256:*}"
    [[ -n "$line" && "$line" != sha256:* ]] || continue
    printf '%s\n' "$line"
  done
}

installed_images="$(ctr -n k8s.io images list -q | normalize_installed)"
while IFS= read -r reference; do
  canonical="$(normalize_reference "$reference")"
  if grep -Fxq "$canonical" <<< "$installed_images"; then
    echo "Image tag already exists; refusing to overwrite: $canonical" >&2
    exit 3
  fi
done <<< "$references"

ctr -n k8s.io images import --platform linux/arm64 images.tar
installed_images="$(ctr -n k8s.io images list -q | normalize_installed)"
while IFS= read -r reference; do
  canonical="$(normalize_reference "$reference")"
  grep -Fxq "$canonical" <<< "$installed_images" || {
    echo "Imported image was not found in containerd: $canonical" >&2
    exit 4
  }
  echo "Imported $canonical"
done <<< "$references"
