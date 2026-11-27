#!/usr/bin/env bash
# Download and verify the pinned sherpa-onnx Android runtime (not tracked by git).
set -euo pipefail

VERSION="1.13.0"
SHA256="28e9b348736d84b610361daf161f5904440d6d516531e896bf9ee8b0ad02764e"
URL="${SHERPA_ONNX_URL:-https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar}"
DEST_DIR="${SHERPA_ONNX_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/app/libs}"
DEST="${DEST_DIR}/sherpa-onnx.aar"
mkdir -p "${DEST_DIR}"

check_hash() { [[ "$(sha256sum "$1" | awk '{print $1}')" == "${SHA256}" ]]; }
if [[ -f "${DEST}" ]] && check_hash "${DEST}"; then
  echo "Already verified: ${DEST}"
  exit 0
fi

tmp="$(mktemp "${DEST_DIR}/.sherpa-onnx.aar.part.XXXXXX")"
trap 'rm -f -- "${tmp}"' EXIT
echo "Downloading sherpa-onnx ${VERSION}…"
curl --fail --location --retry 3 --progress-bar -o "${tmp}" "${URL}"
if ! check_hash "${tmp}"; then
  echo "sherpa-onnx SHA-256 mismatch; existing runtime was not replaced" >&2
  exit 1
fi
mv -f -- "${tmp}" "${DEST}"
echo "Verified and saved to ${DEST}"
