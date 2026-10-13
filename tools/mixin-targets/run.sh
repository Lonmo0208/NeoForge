#!/bin/bash
# Compiles and runs the static mixin reference checker (see MixinTargetCheck.java).
#
# usage: bash tools/mixin-targets/run.sh [joinedJar] [modsDir] [reportFile]
#
# Defaults: the newest neoforge *-joined.jar in projects/neoforge/build/libs, the dev run's mods dir,
# and tools/mixin-targets/report.txt. The ASM jars come from the Gradle cache; the classpath is handed to
# javac/java in Windows form (cygpath), because MSYS does not convert ';'-separated paths in one argument.
set -u
cd "$(dirname "$0")" || exit 1
ROOT=$(cd ../../ && pwd)

JOINED=${1:-$(ls -t "$ROOT"/projects/neoforge/build/libs/*-joined.jar 2>/dev/null | head -1)}
MODS=${2:-$ROOT/projects/neoforge/run/client/mods}
OUT=${3:-report.txt}

findjar() {
    find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/$1/$2" -name "$1-$2.jar" 2>/dev/null | head -1
}
ASM=$(findjar asm 9.10.1)
ASMT=$(findjar asm-tree 9.10.1)
if [ -z "$ASM" ] || [ -z "$ASMT" ]; then
    echo "ASM jars not found in the Gradle cache (expected asm/asm-tree 9.10.1)" >&2
    exit 1
fi

ASMW=$(cygpath -w "$ASM")
ASMTW=$(cygpath -w "$ASMT")

javac -d out -cp "$ASMW;$ASMTW" MixinTargetCheck.java || exit 1
java -cp "out;$ASMW;$ASMTW" MixinTargetCheck "$(cygpath -w "$JOINED")" "$(cygpath -w "$MODS")" "$(cygpath -w "$OUT")"
