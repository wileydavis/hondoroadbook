#!/bin/sh
# The voice both apps ship with (Kokoro, Heart: see .github/workflows/voices.yml). Too big for git, so builds fetch it:
#   app/src/main/assets/tts/kokoro/   the Android app (it shares espeak-ng-data with the built-in fallback voice)
#   ios/Vendor/voice/                 the iPhone app (with its own espeak-ng-data)
# Run once before building; release.yml and testflight.yml run it on every build.
set -eu
cd "$(dirname "$0")/.."
V=kokoro-heart-v1
A=app/src/main/assets/tts/kokoro; I=ios/Vendor/voice
[ -f "$A/.$V" ] && [ -f "$I/.$V" ] && exit 0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
URL="https://github.com/wileydavis/hondoroadbook/releases/download/packs/$V.zip"
curl -fsSL --retry 3 -o "$T/v.zip" "$URL" 2>/dev/null || {
  # behind a proxy that refuses release downloads: the same file by the API
  ID=$(gh api repos/wileydavis/hondoroadbook/releases/tags/packs --jq ".assets[]|select(.name==\"$V.zip\")|.id")
  gh api -H "Accept: application/octet-stream" "repos/wileydavis/hondoroadbook/releases/assets/$ID" > "$T/v.zip"
}
mkdir -p "$T/x"; unzip -q "$T/v.zip" -d "$T/x"
rm -rf "$A" "$I"; mkdir -p "$A" "$I"
for f in model.int8.onnx voices.bin tokens.txt lexicon-us-en.txt LICENSE README.txt; do cp "$T/x/$f" "$A/"; done
cp -R "$T/x/." "$I/"
touch "$A/.$V" "$I/.$V"
du -sh "$A" "$I"
