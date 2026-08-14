#!/usr/bin/env bash
# Build the Windows update helper and copy it into the mod's jar resources
# (src/main/resources/native/win/), where Gradle packages it verbatim.
#
# Requires a Windows cross-compiler. On Debian/Ubuntu:
#   apt install g++-mingw-w64-x86-64
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${ROOT}/build"
# src/main/native/update_helper/ -> ../../resources/native/win = src/main/resources/native/win
RESOURCES_DIR="${ROOT}/../../resources/native/win"

CXX=x86_64-w64-mingw32-g++
if ! command -v "${CXX}" >/dev/null 2>&1; then
    echo "error: ${CXX} not found (apt install g++-mingw-w64-x86-64)" >&2
    exit 1
fi

mkdir -p "${BUILD_DIR}" "${RESOURCES_DIR}"
# -s strips debug symbols: the helper drops from ~13 MB to ~1.3 MB, which keeps
# the mod jar small since the exe ships inside it.
"${CXX}" -std=c++17 -O2 -s -static -municode \
    -o "${BUILD_DIR}/korosoft-update-helper.exe" \
    "${ROOT}/main.cpp"
cp "${BUILD_DIR}/korosoft-update-helper.exe" "${RESOURCES_DIR}/"
echo "built ${RESOURCES_DIR}/korosoft-update-helper.exe"
