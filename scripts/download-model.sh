#!/usr/bin/env bash
# Download Phone Whisper's full-context GigaAM v4 E2E RNN-T conversion for the
# full APK. These four files total about 247 MB and are deliberately gitignored.
# A temporary file is verified before replacing any existing artifact.
set -euo pipefail

RELEASE_TAG="models-gigaam-v4"
MODEL_REPOSITORY="${GIGAAM_MODEL_REPOSITORY:-ivkiwi/phone-whisper}"
BASE_URL="${GIGAAM_MODEL_BASE_URL:-https://github.com/${MODEL_REPOSITORY}/releases/download/${RELEASE_TAG}}"
DEST_DIR="${GIGAAM_MODEL_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/models/gigaam-v4}"
mkdir -p "${DEST_DIR}"

FILES=(
  gigaam_v4_e2e_rnnt_encoder_int8.onnx
  gigaam_v4_e2e_rnnt_decoder.onnx
  gigaam_v4_e2e_rnnt_joint.onnx
  gigaam_v4_e2e_rnnt_tokens.txt
)
declare -A SHA256=(
  [gigaam_v4_e2e_rnnt_encoder_int8.onnx]="55a182f536b0c61eb1bb67214600967858ba4646fb68fe3bcb70549029a65ad8"
  [gigaam_v4_e2e_rnnt_decoder.onnx]="f665598c4e271dfe1641106751bf895dc02517c4ff98a35a2c2a7f2b6b990e17"
  [gigaam_v4_e2e_rnnt_joint.onnx]="0d7888efb5cc6a07d04c4bbe9a5fe87951d16abf687bc11fdfb2046cae753228"
  [gigaam_v4_e2e_rnnt_tokens.txt]="4af4f59269ff664ea6a55838b12fa0cb5e07bd5e019202b4d38b3fbc9275e923"
)

check_hash() {
  local actual
  actual="$(sha256sum "$1" | awk '{print $1}')"
  [[ "${actual}" == "$2" ]]
}

tmp=""
trap '[[ -z "${tmp}" ]] || rm -f -- "${tmp}"' EXIT
for filename in "${FILES[@]}"; do
  dest="${DEST_DIR}/${filename}"
  expected="${SHA256[${filename}]}"
  if [[ -f "${dest}" ]] && check_hash "${dest}" "${expected}"; then
    echo "OK     ${filename}"
    continue
  fi

  echo "Fetch  ${filename}"
  tmp="$(mktemp "${DEST_DIR}/.${filename}.part.XXXXXX")"
  curl --fail --location --retry 3 --progress-bar -o "${tmp}" "${BASE_URL}/${filename}"
  if ! check_hash "${tmp}" "${expected}"; then
    echo "SHA-256 mismatch for ${filename}; existing file was not replaced" >&2
    exit 1
  fi
  mv -f -- "${tmp}" "${dest}"
  tmp=""
  echo "OK     ${filename}"
done

echo "Model ready in ${DEST_DIR}"
