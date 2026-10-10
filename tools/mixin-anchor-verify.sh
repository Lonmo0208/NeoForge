#!/bin/bash
# Runs the bytecode-level injection verifier (tools/mixin-anchor-verify/VerifyMixinAnchors.java).
#
# Compares every injection a patched mod jar declares against the patched game classes: the method the
# injection selects *and* the @At anchor inside it. That second half is what the name-only sweeps
# (mixin-anchor-sweep.sh / mixin-target-sweep.sh) cannot see, and it is where a silently skipped
# injection hides.
#
# Usage: tools/mixin-anchor-verify.sh <modJarDir|modJar> [onlySubstring] [gameClassesDir]
#   default game classes: projects/neoforge/build/classes/java/main;projects/neoforge/build/classes/java/client
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
JARS=${1:-"$ROOT/projects/neoforge/run/client/mods/.neofobric"}
ONLY=${2:-}
GAME=${3:-"$ROOT/projects/neoforge/build/classes/java/main;$ROOT/projects/neoforge/build/classes/java/client"}

CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.ow2.asm
ASM=$(find "$CACHE/asm" -name "asm-9.*.jar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort | tail -1)
ASMT=$(find "$CACHE/asm-tree" -name "asm-tree-9.*.jar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort | tail -1)
if [ -z "$ASM" ] || [ -z "$ASMT" ]; then
    echo "ASM jars not found in $CACHE - set GRADLE_USER_HOME" >&2
    exit 2
fi

# javac/java are Windows binaries: the classpath and every path argument must be in Windows form, or
# the shell's ;-separated lists reach them mangled.
ASM_W=$(cygpath -w "$ASM")
ASMT_W=$(cygpath -w "$ASMT")
OUT_W=$(cygpath -w "$ROOT/tools/mixin-anchor-verify/.build")
JARS_W=$(cygpath -w "$JARS")
GAME_W=$(echo "$GAME" | tr ';' '\n' | while read -r p; do cygpath -w "$p"; done | paste -sd ';')

OUT=$ROOT/tools/mixin-anchor-verify/.build
mkdir -p "$OUT"
# Extra resolution roots: the NeoForge-side mods sitting next to the Fabric ones (sodium, JEI, ...).
EXTRA_W=""
for d in "$ROOT/projects/neoforge/run/client/mods"; do
    [ -d "$d" ] && EXTRA_W="$EXTRA_W$(cygpath -w "$d");"
done
javac -nowarn -d "$OUT_W" -cp "$ASM_W;$ASMT_W" "$ROOT/tools/mixin-anchor-verify/VerifyMixinAnchors.java" || exit 1
java -cp "$OUT_W;$ASM_W;$ASMT_W" VerifyMixinAnchors "$GAME_W" "$JARS_W" "$ONLY" "${EXTRA_W%;}" "${5:-.neofobric-realigned.jar}"
