#!/bin/bash
# Shows what a patched game class does where vanilla did something else.
#
# The injection failures that are left are all "the platform replaced the call this Fabric mixin wraps".
# Answering one needs three things side by side: what the mod wraps, what vanilla had there, and what the
# platform has there now. The vanilla sources live in projects/base (the unpatched baseline), the
# platform's in projects/neoforge/src.
#
# Usage: tools/vanilla-vs-patched.sh <internalClassName> <memberNameOrPattern> [contextLines]
#   e.g. tools/vanilla-vs-patched.sh net/minecraft/world/item/BlockItem getPlaceSound
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLASS=${1:?usage: $0 <internal class name> <member or pattern> [context]}
MEMBER=${2:?usage: $0 <internal class name> <member or pattern> [context]}
CONTEXT=${3:-8}
FILE="$CLASS.java"

VANILLA=$(find "$ROOT/projects/base/build/tmp/.cache/expanded" -path "*$FILE" 2>/dev/null | head -1)
PATCHED=$(find "$ROOT/projects/neoforge/src" -path "*$FILE" 2>/dev/null | head -1)

echo "==== VANILLA  $VANILLA"
if [ -n "$VANILLA" ] && [ -f "$VANILLA" ]; then
    grep -n "$MEMBER" -B 3 -A "$CONTEXT" "$VANILLA" | head -80
else
    echo "  (not found in the baseline tree)"
fi

echo
echo "==== PATCHED  $PATCHED"
if [ -n "$PATCHED" ] && [ -f "$PATCHED" ]; then
    grep -n "$MEMBER" -B 3 -A "$CONTEXT" "$PATCHED" | head -80
else
    echo "  (not found in the platform tree - it may be client-only or unchanged)"
fi
