#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON="$(command -v python3)"
SERVICE_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
mkdir -p "$SERVICE_DIR"
cat > "$SERVICE_DIR/wif-hub.service" <<EOF
[Unit]
Description=WiFi HUB ADB Gateway (Tailscale)
After=network-online.target

[Service]
Type=simple
ExecStart=$PYTHON "$DIR/gateway.py" serve
Restart=always
RestartSec=10
WorkingDirectory=$DIR
NoNewPrivileges=true

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable wif-hub.service
echo "Installed user service. Start with: systemctl --user start wif-hub"
echo "View logs: journalctl --user -u wif-hub -f"
echo "For service before login, an administrator must enable linger: sudo loginctl enable-linger $USER"
