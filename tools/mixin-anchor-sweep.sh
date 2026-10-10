#!/bin/bash
# Checks every injection anchor a Fabric mod's mixins declare against what the NeoForge tree actually
# still has. A missing anchor is not an error anywhere: Mixin skips such an injection in production and
# says nothing, which is how a whole feature can quietly stop working.
#
# Usage: tools/mixin-anchor-sweep.sh <mod source root> [<platform source root>]
#   <mod source root>       e.g. E:/Create-Fly-26.2/src
#   <platform source root>  defaults to projects/neoforge/src (main + client)
#
# Notes:
#  - Only method *names* are compared, so a renamed parameter or a changed descriptor does not show up.
#  - Anchors that name a lambda (lambda$enclosing$N) or an obfuscation-visible synthetic method are listed
#    separately: those names do not appear in any source, so they need the runtime class (javap) instead.
set -u
MOD_SRC=${1:-}
PLATFORM=${2:-$(cd "$(dirname "$0")/.." && pwd)/projects/neoforge/src}
if [ -z "$MOD_SRC" ] || [ ! -d "$MOD_SRC" ]; then
    echo "usage: $0 <mod source root> [platform source root]" >&2
    exit 2
fi

checked=0
missing=0
synthetic=0
for f in $(grep -rl "@Mixin(" --include="*.java" "$MOD_SRC" 2>/dev/null); do
    target=$(grep -o "@Mixin([A-Za-z0-9_.$]*\.class" "$f" | head -1 | sed 's/@Mixin(//; s/\.class//')
    [ -z "$target" ] && continue
    short=$(echo "$target" | sed 's/.*\.//')
    rt=$(find "$PLATFORM" -name "$short.java" 2>/dev/null | head -1)
    if [ -z "$rt" ]; then
        echo "?? $short (no source in the platform tree) <- $(basename "$f")"
        continue
    fi
    while read -r name; do
        [ -z "$name" ] && continue
        checked=$((checked + 1))
        case "$name" in
            lambda\$* | access\$* | *\$[0-9]*)
                synthetic=$((synthetic + 1))
                continue
                ;;
        esac
        if ! grep -q "[ .]$name(" "$rt"; then
            echo "MISSING $short#$name <- $(basename "$f")"
            missing=$((missing + 1))
        fi
    done < <(grep -o 'method = "[A-Za-z0-9_$]*' "$f" | sed 's/method = "//' | sort -u)
done
echo "---- checked $checked anchor(s); $missing missing; $synthetic synthetic (lambda) name(s) to verify with javap"
