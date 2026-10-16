# Firewall and gateway diagnostics

WiF HUB binds the service to the host's **Tailscale IPv4 only** and uses TCP 42424 by default. It does not punch through the host firewall. Tailnet ACLs and firewall rules must both permit the Android controller to connect to the gateway.

## AgentBox firewall issue discovered October 16, 2026

The pre-existing `inet agentbox / input` nftables chain had `policy drop` and only allowed Tailscale TCP ports 22, 3389, and 21118. Those ports were occupied by SSH, RDP, and RustDesk. WiF HUB's TCP 42424 was blocked. **Do not disable the firewall or replace any of those services.**

A narrowly scoped one-time fix is provided:

```bash
cd /home/agent/projects/wif-hub
bash gateway/allow-tailnet-port.sh
```

Run this from an interactive terminal on AgentBox. It will request an administrator password through `sudo`, if necessary. It allows **TCP 42424 only on interface tailscale0**, backs up `/etc/nftables.conf`, preserves existing services, and updates the on-disk rule for the next reboot. No administrative password should be stored in the repository or sent to the app.

Verify from S25 (Termux) when the firewall step is complete:

```bash
curl --noproxy '*' --max-time 4 http://100.65.177.100:42424/v1/info
```

For different machines, configure equivalent Tailscale-interface-only rules for port 42424 and check the app's **Test gateway reachability** action.

### Wireless debugging limitations

ADB pairing works through the **selected computer's ADB server**. The target's pairing dialog provides a temporary port and six-digit code. The wireless debugging main page supplies a separate connection port. WiF HUB now offers a TCP reachability check for both endpoints before pairing.

On stock Android, enabling Wireless debugging and approving a new pairing still require user/device-owner authorization. The helper opens Developer options and Tailscale, but cannot silently grant privileged permissions.
