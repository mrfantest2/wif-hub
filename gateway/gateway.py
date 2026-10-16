#!/usr/bin/env python3
"""WiF HUB gateway: authenticated ADB controller, Tailscale-bound, stdlib-only."""
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

PORT = 8765
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
        p = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=timeout,
                           stdin=subprocess.PIPE if stdin is not None else None)
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
    raise RuntimeError("Tailscale IPv4 not found. Start/sign in to Tailscale before running WiF HUB.")

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

def mdns_services():
    result = run([adb(), "mdns", "services"], 10)
    found = []
    for line in result["output"].splitlines():
        # Typical: adb-XXXX._adb-tls-connect._tcp. 192.168.1.2:45678
        match = re.search(r"([^\s]+\._adb-tls-(?:pairing|connect)\._tcp\.)\s+(\S+):(\d+)", line)
        if match:
            found.append({"service": match[1], "host": match[2], "port": int(match[3]),
                          "kind": "pair" if "pairing" in match[1] else "connect"})
    return {"ok": result["ok"], "services": found, "raw": result["output"]}

def token_ok(header):
    if not header.startswith("Bearer "): return False
    digest = hashlib.sha256(header[7:].encode()).hexdigest()
    with LOCK:
        return any(secrets.compare_digest(digest, x) for x in identity().get("clients", {}).values())

class Handler(BaseHTTPRequestHandler):
    server_version = "WiFHUB/0.1"
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
            self.reply(200, {"ok": True, "name": socket.gethostname(), "version": "0.1",
                             "enrollment": True, "platform": sys.platform})
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
            elif path == "/v1/tcpip":
                serial = safe_serial(body.get("serial"))
                if serial not in [d["serial"] for d in adb_devices()["devices"] if d["state"] == "device"]:
                    raise ValueError("Device must already be ADB-authorized on this computer")
                result = run([adb(), "-s", serial, "tcpip", "5555"], 15)
            elif path == "/v1/reconnect":
                result = run([adb(), "reconnect", "offline"], 12)
            else:
                self.reply(404, {"ok": False, "error": "Unknown action"}); return
            self.reply(200 if result["ok"] else 422, result)
        except ValueError as exc:
            self.reply(400, {"ok": False, "error": str(exc)})

def main():
    p = argparse.ArgumentParser(description="WiF HUB Tailscale ADB gateway")
    p.add_argument("action", nargs="?", choices=["serve", "show-code", "rotate-code"], default="serve")
    p.add_argument("--port", type=int, default=PORT)
    args = p.parse_args()
    with LOCK:
        db = identity()
        if args.action == "rotate-code":
            db["code"] = f"{secrets.randbelow(1000000):06d}"
            save_identity(db)
        if args.action in ("show-code", "rotate-code"):
            print("WiF HUB approval code:", db["code"]); return
    ip = bind_ip()
    server = ThreadingHTTPServer((ip, args.port), Handler)
    print(f"WiF HUB gateway: http://{ip}:{args.port}", flush=True)
    print(f"Host approval code: {db['code']} (changes after each enrollment)", flush=True)
    print("Listening only on Tailscale IPv4. Keep this window running.", flush=True)
    try: server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == "__main__":
    main()
