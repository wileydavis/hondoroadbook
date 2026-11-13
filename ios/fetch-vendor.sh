#!/bin/sh
# The voice's engine: sherpa-onnx (Apache-2.0) and ONNX Runtime (MIT) as prebuilt static xcframeworks, fetched
# into ios/Vendor (git-ignored: about 400 MB unpacked, most of it simulator and macOS slices that never ship).
# Run once before `xcodegen generate`; CI (testflight.yml) runs it on every build.
set -eu
cd "$(dirname "$0")"
V=1.12.21
[ -f "Vendor/.v$V" ] && exit 0
rm -rf Vendor/*.xcframework Vendor/.v* Vendor/build-ios; mkdir -p Vendor
curl -fL --retry 5 -o Vendor/s.tar.bz2 "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/sherpa-onnx-v$V-ios.tar.bz2"
tar xjf Vendor/s.tar.bz2 -C Vendor
mv Vendor/build-ios/sherpa-onnx.xcframework Vendor/
mv Vendor/build-ios/ios-onnxruntime/*/onnxruntime.xcframework Vendor/
rm -rf Vendor/build-ios Vendor/s.tar.bz2 Vendor/onnxruntime.xcframework/macos-arm64_x86_64
touch "Vendor/.v$V"
ls Vendor
