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
  [ -f /workspace/src/pack.mcmeta ] && cp /workspace/src/pack.mcmeta /tmp/jarout/pack.mcmeta
  [ -d /workspace/src/ovm ] && cp -r /workspace/src/ovm /tmp/jarout/ovm
  cd /tmp/jarout && jar cf /workspace/ovm-${VERSION}.jar .
"

echo "Built: $OUT"
jar tf "$OUT"

# Deploy to Prism mods directory if it exists
MODS="$REPO/1.4.7/minecraft/mods"
if [ -d "$MODS" ]; then
  # Remove old ovm jars (including previous versions and Prism duplicate markers).
  rm -f "$MODS"/ovm*.jar "$MODS"/ovm*.jar.disabled
  rm -f "$MODS"/ovm*.duplicate "$MODS"/ovm*.duplicate.disabled
  rm -f "$MODS"/ovm.jar "$MODS"/ovm.jar.disabled

  deploy_name() {
    local base="$1"
    local active="$MODS/$base"
    local disabled="$active.disabled"
    if [ -f "$disabled" ]; then
      cp "$OUT" "$disabled"
      echo "Deployed: $disabled"
    else
      cp "$OUT" "$active"
      echo "Deployed: $active"
    fi
  }

  deploy_name "ovm-${VERSION}.jar"
fi
