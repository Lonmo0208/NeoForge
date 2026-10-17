#!/bin/bash
# NeoFobric: build the installer with a deliberate version number.
#
# Why this exists: a GitHub *release* keeps a git tag in this branch's history forever, and the version
# calculator turns any tag inside the build range into "26.2.0.116.0-beta-26.2-NeoFobric" - a form FML
# refuses to launch. gradle.properties pins `version=`, which makes every build ignore tags; this script
# bumps that pin and then builds, so a release is one command and the version is never accidental.
#
# 用法:
#   bash tools/build-installer.sh                  # 把 26.2.0.<n> 自增 1 后构建
#   bash tools/build-installer.sh 26.2.0.120-26.2-NeoFobric   # 指定版本构建
set -eu
cd "$(dirname "$0")/.."

CURRENT=$(grep -E '^version=' gradle.properties | tail -1 | cut -d= -f2-)
if [ -z "${CURRENT:-}" ]; then
    echo "gradle.properties 里没有 version= 行，先补上再跑" >&2
    exit 1
fi

if [ $# -ge 1 ]; then
    NEW=$1
    # The value goes straight into a sed replacement on a tracked file: reject anything that is not a
    # plain 26.2.0.<n> version (an '&' or '|' in the argument would corrupt gradle.properties).
    if ! printf '%s' "$NEW" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z._-]+)?$'; then
        echo "版本号 '$NEW' 不是 数字.数字.数字.数字[-后缀] 形式，拒绝写进 gradle.properties" >&2
        exit 1
    fi
else
    SUFFIX=$(printf '%s' "$CURRENT" | sed -E 's/^26\.2\.0\.[0-9]+//')
    NUMBER=$(printf '%s' "$CURRENT" | sed -E 's/^26\.2\.0\.([0-9]+).*/\1/')
    if [ "$NUMBER" = "$CURRENT" ]; then
        echo "版本 '$CURRENT' 不是 26.2.0.<n> 形式，请显式给一个版本号" >&2
        exit 1
    fi
    NEW="26.2.0.$((NUMBER + 1))$SUFFIX"
fi

echo "[NeoFobric] 版本 $CURRENT -> $NEW"
sed -i "s|^version=.*|version=$NEW|" gradle.properties
./gradlew :neoforge:installerJar --console=plain

ARTIFACT=$(ls -t projects/neoforge/build/libs/*installer*.jar | head -1)
echo
echo "[NeoFobric] 产物: $ARTIFACT"
echo "[NeoFobric] 自报版本: $(unzip -p "$ARTIFACT" version.json | grep -m1 '"id"')"
echo "[NeoFobric] 记得把这个版本号的改动提交进去（发布提交 = 版本号 + 安装器 ✓）"
