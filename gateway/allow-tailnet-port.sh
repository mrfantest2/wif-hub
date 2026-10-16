#!/usr/bin/env bash
set -euo pipefail
RULE_FILE=/etc/nftables.conf
if [[ ! -f "$RULE_FILE" ]] || ! grep -Fq 'table inet agentbox' "$RULE_FILE"; then
  echo "Unsupported nftables ruleset. No changes made." >&2
  exit 1
fi
if ! grep -Fq 'iifname "tailscale0" tcp dport { 22, 3389, 21118 } accept' "$RULE_FILE" &&
   ! grep -Fq 'iifname "tailscale0" tcp dport { 22, 3389, 21118, 42424 } accept' "$RULE_FILE"; then
  echo "Expected AgentBox rule not found. No changes made." >&2
  exit 1
fi
echo "Allow WiF HUB TCP 42424 only via tailscale0."
sudo nft list chain inet agentbox input > /dev/null
if ! sudo nft list chain inet agentbox input | grep -Eq 'tailscale0.*42424'; then
  sudo nft add rule inet agentbox input iifname "tailscale0" tcp dport 42424 accept
fi
if ! grep -Fq 'iifname "tailscale0" tcp dport { 22, 3389, 21118, 42424 } accept' "$RULE_FILE"; then
  backup="$RULE_FILE.wifhub-$(date +%Y%m%d-%H%M%S).bak"
  sudo cp -p "$RULE_FILE" "$backup"
  sudo python3 - "$RULE_FILE" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
needle='iifname "tailscale0" tcp dport { 22, 3389, 21118 } accept'
assert s.count(needle)==1, "Unexpected ruleset content"
p.write_text(s.replace(needle,'iifname "tailscale0" tcp dport { 22, 3389, 21118, 42424 } accept'))
PY
  echo "Persistent nftables rule saved; backup: $backup"
fi
echo "Firewall allowance active; other services and rules unchanged."
sudo nft list chain inet agentbox input | grep 42424
