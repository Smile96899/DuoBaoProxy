#!/bin/bash
# Builds the gomobile library of go/mobile for the Android / iOS apps.
#
#   ./build_mobile.sh                 # android: arm64 only (release)
#   DEBUG=1 ./build_mobile.sh         # android: arm64 + amd64 (emulator)
#   TARGET=ios ./build_mobile.sh      # iOS xcframework (macOS + Xcode only)
#   VERSION=1.0.0 ./build_mobile.sh
#
# Android output: mobile/android/app/libs/dbcore.aar (Java package
# com.duobao.core.mobile). Needs gomobile/gobind in PATH or in
# $(go env GOPATH)/bin, and ANDROID_HOME / ANDROID_NDK_HOME.
set -e
cd "$(dirname "$0")"
ROOT=$(cd .. && pwd)

TARGET=${TARGET:-android}
VERSION=${VERSION:-dev}
SINGBOX_VERSION=$(go list -m -f '{{.Version}}' github.com/sagernet/sing-box | sed 's/^v//')

GOBIN_DIR=$(go env GOPATH)/bin
export PATH="$GOBIN_DIR:$PATH"
command -v gomobile >/dev/null || { echo "gomobile not found: go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest" >&2; exit 1; }

# gobind copies Go doc comments (some Chinese) into the Java sources; javac
# must not read them in the platform charset (GBK on Chinese Windows).
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dfile.encoding=UTF-8"

TAGS="with_quic,with_wireguard,with_utls,with_gvisor,badlinkname,tfogo_checklinkname0"
LDFLAGS="-s -w -checklinkname=0 -buildid= -X duobaoproxy/mobile.version=$VERSION -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION"

case "$TARGET" in
android)
	if [ -z "$ANDROID_HOME" ]; then
		for d in "$LOCALAPPDATA/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
			[ -d "$d" ] && { ANDROID_HOME=$d; break; }
		done
	fi
	if [ -z "$ANDROID_NDK_HOME" ] && [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
		ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
	fi
	[ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] || { echo "set ANDROID_HOME and ANDROID_NDK_HOME" >&2; exit 1; }
	export ANDROID_HOME ANDROID_NDK_HOME

	ANDROID_TARGET=android/arm64
	[ -n "$DEBUG" ] && [ "$DEBUG" != 0 ] && ANDROID_TARGET=android/arm64,android/amd64
	OUT="$ROOT/mobile/android/app/libs/dbcore.aar"
	mkdir -p "$(dirname "$OUT")"
	gomobile bind -v -target "$ANDROID_TARGET" -androidapi 29 -javapkg com.duobao.core \
		-trimpath -tags "$TAGS" -ldflags "$LDFLAGS" -o "$OUT" ./mobile
	echo "built $OUT ($ANDROID_TARGET, version $VERSION, sing-box $SINGBOX_VERSION, xray $(go list -m -f '{{.Version}}' github.com/xtls/xray-core))"
	;;
ios)
	# The Network Extension has ~50 MB: low-memory buffers, plus DHCP DNS
	# as upstream does on Darwin. Needs macOS with Xcode.
	[ "$(uname -s)" = Darwin ] || { echo "the iOS build needs macOS + Xcode" >&2; exit 1; }
	OUT="$ROOT/mobile/ios/Frameworks/Dbcore.xcframework"
	mkdir -p "$(dirname "$OUT")"
	gomobile bind -v -target ios,iossimulator -iosversion 15.0 -prefix DBC \
		-trimpath -tags "$TAGS,with_low_memory,with_dhcp" -ldflags "$LDFLAGS" -o "$OUT" ./mobile
	echo "built $OUT (version $VERSION, sing-box $SINGBOX_VERSION)"
	;;
*)
	echo "unknown TARGET=$TARGET (android | ios)" >&2
	exit 1
	;;
esac
