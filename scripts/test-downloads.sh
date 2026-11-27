#!/usr/bin/env bash
# Offline failure-path tests; no model files or network access required.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf -- "${TMP}"' EXIT
mkdir -p "${TMP}/model" "${TMP}/bad-source" "${TMP}/runtime"
encoder=gigaam_v4_e2e_rnnt_encoder_int8.onnx
printf 'previous-model' > "${TMP}/model/${encoder}"
printf 'bad-model' > "${TMP}/bad-source/${encoder}"

if GIGAAM_MODEL_BASE_URL="file://${TMP}/bad-source" GIGAAM_MODEL_DIR="${TMP}/model" \
    bash "${ROOT}/scripts/download-model.sh" > "${TMP}/log" 2>&1; then
  echo "ERROR: a corrupt model was accepted" >&2; exit 1
fi
[[ "$(cat "${TMP}/model/${encoder}")" == previous-model ]]
if GIGAAM_MODEL_BASE_URL="file://${TMP}/missing" GIGAAM_MODEL_DIR="${TMP}/model" \
    bash "${ROOT}/scripts/download-model.sh" > "${TMP}/log" 2>&1; then
  echo "ERROR: a missing model was accepted" >&2; exit 1
fi
[[ "$(cat "${TMP}/model/${encoder}")" == previous-model ]]
! find "${TMP}/model" -name '*.part.*' -print -quit | grep -q .

printf 'previous-runtime' > "${TMP}/runtime/sherpa-onnx.aar"
printf 'bad-runtime' > "${TMP}/bad-runtime.aar"
if SHERPA_ONNX_URL="file://${TMP}/bad-runtime.aar" SHERPA_ONNX_DIR="${TMP}/runtime" \
    bash "${ROOT}/scripts/download-sherpa-onnx.sh" > "${TMP}/log" 2>&1; then
  echo "ERROR: a corrupt runtime was accepted" >&2; exit 1
fi
[[ "$(cat "${TMP}/runtime/sherpa-onnx.aar")" == previous-runtime ]]
! find "${TMP}/runtime" -name '*.part.*' -print -quit | grep -q .
echo 'PASS: failed downloads preserve previous files and remove partial files'
