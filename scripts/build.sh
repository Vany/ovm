#!/bin/bash
# Compile and package OVM mod jar.
# Run from anywhere; output: ovm-<version>.jar in repo root.
set -e

REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE=veinminer-dev
VERSION=$(grep 'VERSION = ' "$REPO/src/com/ovm/OvmMod.java" | sed 's/.*"\(.*\)".*/\1/')
OUT="$REPO/ovm-${VERSION}.jar"

docker run --rm -v "$REPO:/workspace" $IMAGE bash -c "
  bash /workspace/scripts/compile.sh > /dev/null
  mkdir -p /tmp/jarout/com/ovm
  cp /opt/forge/bin/minecraft/com/ovm/*.class /tmp/jarout/com/ovm/
  cp /workspace/src/com/ovm/mcmod.info /tmp/jarout/mcmod.info
  [ -f /workspace/src/pack.png ] && cp /workspace/src/pack.png /tmp/jarout/pack.png
  cd /tmp/jarout && jar cf /workspace/ovm-${VERSION}.jar .
"

echo "Built: $OUT"
jar tf "$OUT"
