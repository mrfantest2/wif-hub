# WiFi HUB

## Automatic Tailscale discovery (v0.1.4)

Open the native Android app and the **Scan** tab starts searching immediately. It attempts
(a) a WiFi HUB LAN broadcast beacon on the local Wi-Fi, (b) common Tailscale MagicDNS gateway
names when DNS is enabled, (c) previously approved gateways, and (d) an existing local SSH
fallback tunnel. The first reachable WiFi HUB gateway returns a read-only **tailnet inventory**
(including Linux, Windows, and Android peers, online/offline). The app displays machines and
their tailnet addresses, checks which run WiFi HUB, and lets you select a computer or phone
without entering its IP address.

**Security:** Discovery and peer listing are read-only and only accessible via the tailnet-bound
gateway endpoint; ADB pairing, USB/Wireless Debugging controls, and any state-changing actions
still require device authorization and per-gateway enrollment. A host approval code is requested
only once per controller/host and never shipped in the public repository.

**Important technical limitation:** Tailscale's Android VPN app does not expose its complete
peer inventory to unrelated Android apps. Discovery of *remote* machines without any already
reachable gateway, shared LAN, MagicDNS resolution, or optional Tailscale API authorization
cannot be guaranteed. WiFi HUB does **not** brute-force Tailscale's 100.64/10 address space.
For best zero-entry onboarding, run a gateway on one nearby computer on the same Wi-Fi and
allow local UDP 42426. The gateway then returns the wider tailnet's peer list. Other tailnets
and device names are supported; the app is not tied to specific IP addresses.


Multi-host, Tailscale-powered ADB management from Android. Open-source personal/device-lab control without cloud hosting.

## Architecture

- `android/` — native Android Java controller and phone setup helper UI.
- `gateway/gateway.py` — Python 3 standard-library gateway on Windows/Linux, bound **only to the computer's Tailscale IPv4**, port 42424.
- Tailscale transports traffic; **separate one-time host enrollment** protects control commands. Each Android install gets its own host-specific credential, kept in app-private preferences. No secrets go in Git.
- The selected gateway executes ADB locally. Different gateways have distinct ADB device lists and authorizations.
- One known gateway can return Tailscale peers to discover additional gateway instances. mDNS ADB discovery works when Android and the gateway can access the same local multicast network. Tailscale does not magically forward mDNS broadcasts.

## Computer setup (Windows or Linux)

Install Python 3, ADB platform-tools, and Tailscale. Sign into the **same tailnet** and ensure tailnet ACLs permit your Android client to reach the computer on TCP/42424. On Windows, if required, allow inbound TCP/42424 on the Tailscale network interface.

```sh
python gateway/gateway.py serve
```

Use `python3` instead of `python` on Linux if appropriate. Keep the service running for remote access.

Gateway shows its Tailscale IP and a six-digit computer enrollment code in the terminal. On Android choose **Enroll gateway**, enter its Tailscale IP, port 42424, and the approval code. The code rotates on successful enrollment. Later run `python gateway/gateway.py show-code` to inspect it, or `rotate-code` to invalidate the previous unused code.

**Windows note**: if `tailscale` is not on PATH, add `C:\\Program Files\\Tailscale` to PATH; if `adb` is missing install Android SDK Platform Tools. Grant inbound access to this port only through the private Tailscale interface.

## Run in the background

**Linux (systemd user service)**: Run \`bash gateway/install-linux-user.sh\`, then \`systemctl --user start wif-hub\`. The installer enables startup on user login. To keep it active without login, use \`sudo loginctl enable-linger $USER\` if permitted. Check \`systemctl --user status wif-hub\`.

**Windows (hidden scheduled task)**: Run PowerShell \`gateway/install-windows.ps1\` once. It registers a hidden per-user task at login, without additional PowerShell windows. Start manually with \`Start-ScheduledTask -TaskName "WiFi HUB Gateway"\` only if no instance already occupies port 42424.

**Fallback**: If the gateway service is unreachable, an authorized remote-desktop tool can be used separately to restart it on that computer. WiFi HUB does not embed or bypass remote-desktop credentials.

## Current features (v0.1.3)

- Four compact navigation tabs: Hosts, Devices, Pair, Helper.
- Windows/Linux gateway selection with per-host enrollment credentials.
- Tailnet peer inventory and local Android mDNS ADB discovery.
- Separate Android pairing port and wireless debugging connection port.
- Authenticated TCP reachability preflight on the selected computer.
- Explicit, authorized-device-only request to enable Android Wireless Debugging.
- Manual USB-authorized ADB TCP/IP 5555 switching as an optional legacy alternative.
- Tailscale app/developer settings shortcuts on Android.
- Startup helpers for Windows Task Scheduler and Linux systemd user services.

**Existing AgentBox installations:** the nftables input policy drops WiFi HUB TCP 42424 until the operator authorizes the targeted rule. See [gateway/FIREWALL.md](gateway/FIREWALL.md). This is a one-time administrator step, not a reason to disable the firewall.

## Android operation

1. Open WiFi HUB on Android. Enroll at least one host.
2. Select **Master PC** or **AgentBox**, or any enrolled gateway. **Discover peers** lists online tailnet devices, offers Android targets, and polls Tailscale's peer list through that gateway and probes only peers for the WiFi HUB server.
3. **Scan** lists ADB devices and local mDNS debugging services on that host.
4. **Pair** uses IP + pairing port + six-digit Android code (Android Settings → Developer options → Wireless debugging → Pair with pairing code).
5. **Connect** uses IP + a **different** connection port from the main Wireless debugging screen. Tapping Pair doesn't automatically make the pairing endpoint the connection port.
6. If already authorized through USB or ADB, **TCP/IP 5555** can request legacy ADB-over-TCP on that device. A subsequent connect to its reachable address is still required.

## Important limitations

An ordinary unprivileged Android app **cannot silently enable Wireless debugging, approve USB debugging, or generate/approve the secure pairing code on another phone**. The Helper tab opens developer settings for user approval. USB authorization remains physically or interactively controlled by Android. A rooted, device-owner, or privileged helper could implement additional platform-specific actions, but these are intentionally not assumed here.

Tailscale encryption and same-tailnet membership are **not themselves authorization** for ADB control. Both tailnet access and host enrollment are enforced. This app does not support open Internet addresses as ADB targets and exposes no arbitrary remote shell endpoint. Pairing does not bypass device consent.

## Build

```sh
gradle --project-dir android assembleDebug
```

APK output: `android/app/build/outputs/apk/debug/app-debug.apk`.

## API

- Public: `GET /v1/info`, `POST /v1/enroll`.
- Authenticated: `GET /v1/state`, `GET /v1/devices`, `GET /v1/mdns`, `GET /v1/peers`.
- Authenticated writes: `POST /v1/pair`, `/v1/connect`, `/v1/disconnect`, `/v1/tcpip`, `/v1/reconnect`, `/v1/probe`, `/v1/wifi-enable`.
- Auth: `Authorization: Bearer <per-host enrollment token>`.

Only local-authorized devices and eligible private/Tailscale target addresses are supported.
