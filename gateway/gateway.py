#!/usr/bin/env python3
"""WiFi HUB gateway: authenticated ADB controller, Tailscale-bound, stdlib-only."""
from __future__ import annotations
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PORT = 42424
APP_DIR = Path.home() / ".config" / "wif-hub"
IDENTITY_FILE = APP_DIR / "identity.json"
LOCK = threading.RLock()
FAILURES = {}

def save_identity(obj):
    APP_DIR.mkdir(parents=True, exist_ok=True)
    tmp = IDENTITY_FILE.with_suffix(".tmp")
    with open(tmp, "w", encoding="utf8") as f:
        json.dump(obj, f, indent=2)
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, IDENTITY_FILE)

def identity():
    if not IDENTITY_FILE.exists():
        save_identity({"code": f"{secrets.randbelow(1000000):06d}", "clients": {}, "created": time.time()})
    with open(IDENTITY_FILE, encoding="utf8") as f:
        return json.load(f)

def run(args, timeout=15, stdin=None):
    try:
        p = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=timeout)
        return {"ok": p.returncode == 0, "output": (p.stdout + "\n" + p.stderr).strip()[:20000],
                "exit": p.returncode}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"ok": False, "output": str(exc)[:500], "exit": -1}

def tailnet():
    binary = shutil.which("tailscale")
    if not binary:
        return {"self": None, "peers": [], "error": "tailscale not installed"}
    output = run([binary, "status", "--json"], 8)
    if not output["ok"]:
        return {"self": None, "peers": [], "error": output["output"]}
    try:
        doc = json.loads(output["output"])
        def info(node):
            ips = node.get("TailscaleIPs") or []
            return {"name": node.get("HostName") or "", "ip": next((x for x in ips if ":" not in x), ""),
                    "online": node.get("Online", True), "os": node.get("OS", "")}
        return {"self": info(doc.get("Self") or {}), "peers": [info(p) for p in (doc.get("Peer") or {}).values()]}
    except (ValueError, KeyError):
        return {"self": None, "peers": [], "error": "invalid tailscale status"}

def bind_ip():
    data = tailnet().get("self")
    if data and data.get("ip"):
        return data["ip"]
    raise RuntimeError("Tailscale IPv4 not found. Start/sign in to Tailscale before running WiFi HUB.")

def validate_target(host, port):
    if not isinstance(host, str) or len(host) > 253: raise ValueError("Invalid IP")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ValueError("Enter a numeric IPv4 or IPv6 address") from exc
    if not (ip.is_private or ip.is_loopback or ip in ipaddress.ip_network("100.64.0.0/10")):
        raise ValueError("Only LAN or tailnet addresses are accepted")
    if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
        raise ValueError("Port must be 1-65535")
    return f"[{host}]:{port}" if ip.version == 6 else f"{host}:{port}"

def safe_serial(serial):
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9._:\-\[\]]{1,160}", serial):
        raise ValueError("Invalid device serial")
    return serial

def adb():
    return shutil.which("adb") or "adb"

def adb_devices():
    result = run([adb(), "devices", "-l"])
    devices = []
    for line in result["output"].splitlines():
        if line.startswith("List of devices") or not line.strip() or line.startswith("*"): continue
        match = re.match(r"^(\S+)\s+(device|offline|unauthorized|recovery|sideload)(?:\s+(.*))?$", line)
        if match:
            devices.append({"serial": match[1], "state": match[2], "details": match[3] or ""})
    return {"ok": result["ok"], "devices": devices, "raw": result["output"]}

def enable_wireless_debugging(serial):
    """Best-effort on an *already authorized* ADB serial; never overrides pairing consent."""
    state = run([adb(), "-s", serial, "shell", "settings", "get", "global", "adb_wifi_enabled"], 10)
    if not state["ok"]:
        return {"ok": False, "output": "Unable to read Wireless debugging state: " + state["output"]}
    if state["output"].strip() == "1":
        return {"ok": True, "output": "Wireless debugging already reports enabled. Use the connection port shown in Android settings."}
    changed = run([adb(), "-s", serial, "shell", "settings", "put", "global", "adb_wifi_enabled", "1"], 10)
    if not changed["ok"]:
        return {"ok": False, "output": "Android denied setting change; enable in Developer options. " + changed["output"]}
    verify = run([adb(), "-s", serial, "shell", "settings", "get", "global", "adb_wifi_enabled"], 10)
    if verify["ok"] and verify["output"].strip() == "1":
        return {"ok": True, "output": "Android reports Wireless debugging enabled. Open the phone's Developer options to confirm its port and pairing approval."}
    return {"ok": False, "output": "Wireless debugging toggle did not remain enabled; manually enable in Developer options."}


def mdns_services():
    result = run([adb(), "mdns", "services"], 10)
    found = []
    for line in result["output"].splitlines():
        # adb mdns services format: <instance> <service-type> <ip:port>
        parts = line.split()
        if len(parts) < 3: continue
        name, service, endpoint = parts[:3]
        if service not in ("_adb-tls-pairing._tcp", "_adb-tls-connect._tcp"): continue
        try:
            host, port = endpoint.rsplit(":", 1)
            target = validate_target(host.strip("[]"), int(port))
            found.append({"service": name, "host": host.strip("[]"),
                          "port": int(port), "kind": "pair" if "pairing" in service else "connect"})
        except (ValueError, IndexError): pass
    return {"ok": result["ok"], "services": found, "raw": result["output"]}

def token_ok(header):
    if not header.startswith("Bearer "): return False
    digest = hashlib.sha256(header[7:].encode()).hexdigest()
    with LOCK:
        return any(secrets.compare_digest(digest, x) for x in identity().get("clients", {}).values())

class Handler(BaseHTTPRequestHandler):
    server_version = "WiFiHUB/0.2"
    def log_message(self, fmt, *args):
        # Prevent bearer token or pairing code from appearing in access logs
        print(f"{self.address_string()} {self.command} {urlsplit(self.path).path} {args[-1] if args else ''}", flush=True)

    def reply(self, status, body):
        payload = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "null")
        self.end_headers()
        self.wfile.write(payload)

    def parse(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length < 0 or length > 4096: raise ValueError("Request body too large")
        obj = json.loads(self.rfile.read(length) or b"{}")
        if not isinstance(obj, dict): raise ValueError("Expected JSON object")
        return obj

    def authorized(self):
        if token_ok(self.headers.get("Authorization", "")): return True
        self.reply(401, {"ok": False, "error": "Host enrollment required"})
        return False

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/v1/info":
            self.reply(200, {"ok": True, "name": socket.gethostname(), "version": "0.2",
                             "enrollment": True, "platform": sys.platform})
            return
        if path == "/v1/discover":
            # Only the minimal tailnet inventory is available anonymously.
            # This interface is bound to the tailnet IPv4, not the public internet.
            data = tailnet()
            self.reply(200, {"ok": "error" not in data, "self": data.get("self"),
                             "peers": data.get("peers", []), "error": data.get("error", "")})
            return
        if not self.authorized(): return
        if path == "/v1/state":
            self.reply(200, {"ok": True, "host": socket.gethostname(), "tailnet": tailnet().get("self"),
                             "adb": adb_devices(), "mdns": mdns_services()})
        elif path == "/v1/peers":
            self.reply(200, {"ok": True, **tailnet()})
        elif path == "/v1/devices":
            self.reply(200, adb_devices())
        elif path == "/v1/mdns":
            self.reply(200, mdns_services())
        else:
            self.reply(404, {"ok": False, "error": "Unknown route"})

    def do_POST(self):
        path = urlsplit(self.path).path
        try: body = self.parse()
        except (ValueError, json.JSONDecodeError) as exc:
            self.reply(400, {"ok": False, "error": str(exc)}); return
        if path == "/v1/enroll":
            addr = self.client_address[0]
            with LOCK:
                stamp, count = FAILURES.get(addr, (0, 0))
                if time.time() - stamp > 300: count = 0
                if count >= 5:
                    self.reply(429, {"ok": False, "error": "Too many attempts; try again later"}); return
                db = identity()
                if not secrets.compare_digest(str(body.get("code", "")), db["code"]):
                    FAILURES[addr] = (time.time(), count + 1)
                    self.reply(403, {"ok": False, "error": "Incorrect host approval code"}); return
                name = re.sub(r"[^a-zA-Z0-9 ._-]", "", str(body.get("client", "Android"))[:48]) or "Android"
                token = secrets.token_urlsafe(32)
                db.setdefault("clients", {})[name + "-" + secrets.token_hex(3)] = hashlib.sha256(token.encode()).hexdigest()
                db["code"] = f"{secrets.randbelow(1000000):06d}"
                save_identity(db)
                FAILURES.pop(addr, None)
            self.reply(200, {"ok": True, "token": token, "name": socket.gethostname()})
            print("Enrolled a client. New approval code: " + db["code"], flush=True)
            return
        if not self.authorized(): return
        try:
            if path in ("/v1/pair", "/v1/connect", "/v1/disconnect"):
                target = validate_target(body.get("host"), body.get("port"))
                if path == "/v1/pair":
                    code = str(body.get("code", ""))
                    if not re.fullmatch(r"\d{6}", code): raise ValueError("Six-digit Android pairing code required")
                    result = run([adb(), "pair", target], 25, code + "\n")
                    result["output"] = result["output"].replace(code, "[REDACTED]")
                elif path == "/v1/connect":
                    result = run([adb(), "connect", target], 18)
                else:
                    result = run([adb(), "disconnect", target], 12)
            elif path == "/v1/probe":
                endpoint = validate_target(body.get("host"), body.get("port"))
                host, port = body["host"], body["port"]
                try:
                    with socket.create_connection((host, port), timeout=3):
                        pass
                    result = {"ok": True, "output": f"TCP endpoint {endpoint} is reachable"}
                except (socket.timeout, OSError) as exc:
                    result = {"ok": False, "output": f"TCP endpoint {endpoint}: {exc}"}
            elif path == "/v1/tcpip":
                serial = safe_serial(body.get("serial"))
                if serial not in [d["serial"] for d in adb_devices()["devices"] if d["state"] == "device"]:
                    raise ValueError("Device must already be ADB-authorized on this computer")
                result = run([adb(), "-s", serial, "tcpip", "5555"], 15)
            elif path == "/v1/wifi-enable":
                serial = safe_serial(body.get("serial"))
                if serial not in [d["serial"] for d in adb_devices()["devices"] if d["state"] == "device"]:
                    raise ValueError("Device must already be ADB-authorized on this computer")
                result = enable_wireless_debugging(serial)
            elif path == "/v1/reconnect":
                result = run([adb(), "reconnect", "offline"], 12)
            else:
                self.reply(404, {"ok": False, "error": "Unknown action"}); return
            self.reply(200 if result["ok"] else 422, result)
        except ValueError as exc:
            self.reply(400, {"ok": False, "error": str(exc)})

def discovery_beacon(tail_ip, port):
    """Same-LAN UDP advertisement, no ADB control or credentials exposed."""
    signature = b"WIFIHUB_DISCOVER_V1"
    response = json.dumps({"kind": "wifi-hub-gateway", "ip": tail_ip,
                           "port": port, "name": socket.gethostname()}).encode("utf-8")
    try:
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.bind(("0.0.0.0", 42426))
        udp.settimeout(1.0)
    except OSError as exc:
        print(f"LAN discovery beacon unavailable: {exc}", flush=True)
        return
    with udp:
        while True:
            try:
                data, client = udp.recvfrom(256)
                if data != signature:
                    continue
                source = ipaddress.ip_address(client[0])
                if not source.is_private or source.is_loopback:
                    continue
                udp.sendto(response, client)
            except socket.timeout:
                continue
            except OSError:
                break


def main():
    p = argparse.ArgumentParser(description="WiFi HUB Tailscale ADB gateway")
    p.add_argument("action", nargs="?", choices=["serve", "show-code", "rotate-code"], default="serve")
    p.add_argument("--port", type=int, default=PORT)
    args = p.parse_args()
    with LOCK:
        db = identity()
        if args.action == "rotate-code":
            db["code"] = f"{secrets.randbelow(1000000):06d}"
            save_identity(db)
        if args.action in ("show-code", "rotate-code"):
            print("WiFi HUB approval code:", db["code"]); return
    ip = bind_ip()
    server = ThreadingHTTPServer((ip, args.port), Handler)
    threading.Thread(target=discovery_beacon, args=(ip, args.port), daemon=True).start()
    print(f"WiFi HUB gateway: http://{ip}:{args.port}", flush=True)
    print(f"Host approval code: {db['code']} (changes after each enrollment)", flush=True)
    print("Listening only on Tailscale IPv4. Keep this window running.", flush=True)
    try: server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == "__main__":
    main()
