# WiF HUB

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

## Android operation

1. Open WiF HUB on Android. Enroll at least one host.
2. Select **Master PC** or **AgentBox**, or any enrolled gateway. **Discover peers** polls Tailscale's peer list through that gateway and probes only peers for the WiF HUB server.
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
- Authenticated writes: `POST /v1/pair`, `/v1/connect`, `/v1/disconnect`, `/v1/tcpip`, `/v1/reconnect`.
- Auth: `Authorization: Bearer <per-host enrollment token>`.

Only local-authorized devices and eligible private/Tailscale target addresses are supported.
