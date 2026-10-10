#!/bin/bash
# Full-game census of the vanilla call sites NeoForge took away (see vanilla-call-diff/CallDiff.java).
#
# Those are the points a Fabric mixin can find nothing to wrap at, and therefore the points a
# "vanilla-shaped door" would have to go. Usage: tools/vanilla-call-diff.sh [reportFile]
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
REPORT=${1:-}
CACHE=${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.ow2.asm
ASM=$(find "$CACHE/asm" -name "asm-9.*.jar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort | tail -1)
ASMT=$(find "$CACHE/asm-tree" -name "asm-tree-9.*.jar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort | tail -1)
[ -z "$ASM" ] && { echo "ASM jars not found" >&2; exit 2; }

OUT=$(cygpath -w "$ROOT/tools/vanilla-call-diff/.build")
mkdir -p "$ROOT/tools/vanilla-call-diff/.build"
javac -nowarn -d "$OUT" -cp "$(cygpath -w "$ASM");$(cygpath -w "$ASMT")" \
    "$ROOT/tools/vanilla-call-diff/CallDiff.java" || exit 1

CLEAN=$(cygpath -w "$ROOT/projects/neoforge/build/neodev/artifacts/clean/joined.jar")
PATCHED="$(cygpath -w "$ROOT/projects/neoforge/build/classes/java/main");$(cygpath -w "$ROOT/projects/neoforge/build/classes/java/client")"
REPORT_ARG=""
[ -n "$REPORT" ] && REPORT_ARG=$(cygpath -w "$REPORT")
java -cp "$OUT;$(cygpath -w "$ASM");$(cygpath -w "$ASMT")" CallDiff "$CLEAN" "$PATCHED" $REPORT_ARG
