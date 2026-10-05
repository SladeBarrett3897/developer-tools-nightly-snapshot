#!/usr/bin/env sh
set -eu
test -n "${INFRAI_API_KEY:-}" || { echo "Set INFRAI_API_KEY before running"; exit 2; }
test -n "${SNAPSHOT_BUCKET:-}" || { echo "Set SNAPSHOT_BUCKET to a unique verification bucket"; exit 2; }
mkdir -p build/classes
javac -d build/classes $(find src/main/java -name '*.java')
java -cp build/classes dev.learning.snapshot.DeveloperToolsSnapshotService --once-cleanup
