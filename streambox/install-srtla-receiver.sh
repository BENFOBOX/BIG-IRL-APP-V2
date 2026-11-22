#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Run this script with sudo." >&2
    exit 1
fi

readonly REVISION="2b19d400d88f2a35ad225d2ea048c04335e69803"
readonly SOURCE_DIR="/opt/openirl-srtla-${REVISION}"
readonly BUILD_DIR="${SOURCE_DIR}/build"
readonly SERVICE_FILE="/etc/systemd/system/bigirl-srtla-receiver.service"

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install --yes cmake g++ git libspdlog-dev

if [[ ! -d "${SOURCE_DIR}/.git" ]]; then
    if [[ -e "${SOURCE_DIR}" ]]; then
        echo "Refusing to use non-Git source path: ${SOURCE_DIR}" >&2
        exit 1
    fi
    git clone --filter=blob:none --no-checkout https://github.com/OpenIRL/srtla.git "${SOURCE_DIR}"
fi

git -C "${SOURCE_DIR}" fetch --depth 1 origin "${REVISION}"
git -C "${SOURCE_DIR}" checkout --detach "${REVISION}"
git -C "${SOURCE_DIR}" submodule update --init --recursive
cmake -S "${SOURCE_DIR}" -B "${BUILD_DIR}" -DCMAKE_BUILD_TYPE=Release
cmake --build "${BUILD_DIR}" --target srtla_rec --parallel 1
install -m 0755 "${BUILD_DIR}/srtla_rec" /usr/local/bin/srtla_rec

cat > "${SERVICE_FILE}" <<'EOF'
[Unit]
Description=BIG IRL SRTLA receiver
After=network-online.target bigirl-receiver.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/srtla_rec --srtla_port 10012 --srt_hostname 127.0.0.1 --srt_port 10000 --log_level info
Restart=on-failure
RestartSec=2
User=nobody
Group=nogroup
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now bigirl-srtla-receiver.service
systemctl --no-pager --full status bigirl-srtla-receiver.service
