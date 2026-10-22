#!/bin/bash
# Builds DuoBaoProxyCore for the current or requested platform.
#
#   ./build_core.sh                      # host platform, output ./dist/<os>-<arch>/
#   GOOS=linux GOARCH=amd64 ./build_core.sh
#   VERSION=1.0.0 ./build_core.sh
set -e
cd "$(dirname "$0")"

GOOS=${GOOS:-$(go env GOOS)}
GOARCH=${GOARCH:-$(go env GOARCH)}
VERSION=${VERSION:-dev}
DEST=${DEST:-dist/$GOOS-$GOARCH}

SINGBOX_VERSION=$(go list -m -f '{{.Version}}' github.com/sagernet/sing-box | sed 's/^v//')
TAGS="with_quic,with_wireguard,with_utls,with_gvisor,with_clash_api"
LDFLAGS="-w -s -X main.Version=$VERSION -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION"

mkdir -p "$DEST"
[ "$GOOS" == "windows" ] && CORE_NAME=DuoBaoProxyCore.exe || CORE_NAME=DuoBaoProxyCore

export CGO_ENABLED=0 GOOS GOARCH
go build -trimpath -tags "$TAGS" -ldflags "$LDFLAGS" -o "$DEST/$CORE_NAME" ./cmd/duobaoproxy-core
echo "built $DEST/$CORE_NAME (sing-box $SINGBOX_VERSION, xray $(go list -m -f '{{.Version}}' github.com/xtls/xray-core))"
