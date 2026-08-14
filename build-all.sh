#!/usr/bin/env bash
# Full Korosoft-Core build: compile the Windows update helper (if the toolchain
# is available), then build the Fabric mod with Loom.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -x "${ROOT}/src/main/native/update_helper/build.sh" ]]; then
    "${ROOT}/src/main/native/update_helper/build.sh"
else
    echo "warning: update-helper build script not found; skipping native helper (Windows updates will fail)" >&2
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"
export PATH="${JAVA_HOME}/bin:${PATH}"

cd "${ROOT}"
./gradlew build --no-daemon
