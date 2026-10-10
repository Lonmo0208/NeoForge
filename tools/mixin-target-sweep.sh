#!/bin/bash
# Checks the *inner* anchors of a Fabric mod's injections - the "target" of an @Inject/@Redirect/
# @WrapOperation, which points at a call inside the target method - against the platform tree.
#
# This is the complement of mixin-anchor-sweep.sh: that one checks the method an injection lives in,
# this one checks the call it hooks. The platform rewrites vanilla call sites (NeoForge replaces whole
# primitives), so a method can still be there while the call inside it is gone - and Mixin skips such an
# injection silently, in production, which is how a feature can stop working with nothing in any log.
#
# Usage: tools/mixin-target-sweep.sh <mod source root> [<platform source root>]
#
# Only method *names* on the named owner are compared; the descriptor is printed next to each finding so
# the few real ones can be confirmed with javap.
set -u
MOD_SRC=${1:-}
PLATFORM=${2:-$(cd "$(dirname "$0")/.." && pwd)/projects/neoforge/src}
if [ -z "$MOD_SRC" ] || [ ! -d "$MOD_SRC" ]; then
    echo "usage: $0 <mod source root> [platform source root]" >&2
    exit 2
fi

checked=0
missing=0
for f in $(grep -rl "@Mixin(" --include="*.java" "$MOD_SRC" 2>/dev/null); do
    # target="L<owner>;<name>(<desc>)..." (the descriptor is optional in the source)
    while read -r spec; do
        owner=$(echo "$spec" | sed 's/^L//; s/;.*//')
        name=$(echo "$spec" | sed 's/^[^;]*;//; s/(.*//')
        desc=$(echo "$spec" | sed 's/^[^;]*;//' | sed 's/^[^(]*(//; s/)$//')
        [ -z "$owner" ] || [ -z "$name" ] && continue
        short=$(echo "$owner" | sed 's#.*/##')
        checked=$((checked + 1))
        rt=$(find "$PLATFORM" -name "$short.java" 2>/dev/null | head -1)
        if [ -z "$rt" ]; then
            echo "?? $short#$name (no source in the platform tree) <- $(basename "$f")"
            continue
        fi
        if ! grep -q "[ .]$name(" "$rt"; then
            echo "MISSING $short#$name($desc) <- $(basename "$f")"
            missing=$((missing + 1))
        fi
    done < <(grep -o 'target = "L[A-Za-z0-9_/$]*;[A-Za-z0-9_$<>]*([^"]*)' "$f" | sed 's/target = "//' | sort -u)
done
echo "---- checked $checked inner anchor(s); $missing name(s) missing on their owner"
