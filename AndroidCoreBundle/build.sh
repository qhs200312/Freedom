#!/usr/bin/env bash
set -euo pipefail

OUTPUT="${1:-../V2rayNG/app/libs/libcores.aar}"
SING_BOX_VERSION="$(go list -m -f '{{.Version}}' github.com/sagernet/sing-box | sed 's/^v//')"
TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird"
LDFLAGS="-X github.com/sagernet/sing-box/constant.Version=${SING_BOX_VERSION} -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -s -w -buildid= -checklinkname=0"

go mod download
gomobile bind -v -o "${OUTPUT}" -target android -androidapi 24 -libname=cores \
  -trimpath -buildvcs=false -ldflags "${LDFLAGS}" -tags "${TAGS}" \
  github.com/2dust/AndroidLibXrayLite \
  github.com/sagernet/sing-box/experimental/libbox \
  github.com/qhs200312/Freedom/AndroidCoreBundle
