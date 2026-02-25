#!/bin/bash
# Compile OVM mod inside the Docker container.
# Run from anywhere; all paths are absolute.
set -e

SRC=/workspace/src/com/ovm
OUT=/opt/forge/bin/minecraft
OUT_PKG=$OUT/com/ovm

mkdir -p "$OUT_PKG"

javac -source 1.6 -target 1.6 -encoding UTF-8 \
  -cp "/opt/forge/bin/minecraft:/opt/forge/lib/*:/opt/forge/jars/bin/minecraft.jar:/opt/forge/jars/bin/lwjgl.jar:/opt/forge/jars/bin/lwjgl_util.jar" \
  -d "$OUT" \
  "$SRC"/*.java

# mcmod.info must be in the same package directory as the @Mod class
cp "$SRC/mcmod.info" "$OUT_PKG/mcmod.info"

echo "Compiled classes:"
ls -1 "$OUT_PKG"/*.class

echo "Bytecode version:"
javap -verbose "$OUT_PKG/OvmMod.class" | grep "major version"
