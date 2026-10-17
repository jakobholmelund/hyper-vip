#!/usr/bin/env bash
# Builds the signed release APK into dist/hyper-vip.apk, installing the Android SDK first if needed.
# Needs JDK 17+ and network access to dl.google.com.
set -euo pipefail
cd "$(dirname "$0")/.."

# The version code is the commit count, which a shallow clone would undercount.
if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --quiet --unshallow
fi

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
REPO="https://dl.google.com/android/repository"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools into $SDK"
  zip_name=$(curl -fsSL "$REPO/repository2-3.xml" | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.stdin).getroot()
for pkg in root.iter("remotePackage"):
    if pkg.get("path") != "cmdline-tools;latest":
        continue
    for archive in pkg.iter("archive"):
        if (archive.findtext("host-os") or "") == "linux":
            print(archive.find("complete").findtext("url"))
            sys.exit()
')
  tmp=$(mktemp -d)
  curl -fsSL "$REPO/$zip_name" -o "$tmp/cmdline-tools.zip"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_HOME="$SDK"
# Newer command-line tools don't ask about licenses and exit early, which breaks `yes`'s pipe.
(yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1) || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-36" "build-tools;36.0.0" > /dev/null

# The wrapper's distribution download can be blocked in sandboxes; an installed Gradle of the
# same version works just as well.
if command -v gradle > /dev/null && gradle --version 2> /dev/null | grep -q "^Gradle 8.14.3"; then
  GRADLE=gradle
else
  GRADLE=./gradlew
fi

"$GRADLE" --no-daemon testDebugUnitTest assembleRelease

mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/hyper-vip.apk
echo "Built dist/hyper-vip.apk"
