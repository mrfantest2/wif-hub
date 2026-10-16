import hashlib
import unittest
from unittest.mock import patch
import gateway

class GatewayTests(unittest.TestCase):
    def test_tailnet_lan_allowed(self):
        for ip in ("100.99.16.99", "192.168.1.172", "10.1.2.3", "172.16.4.20", "127.0.0.1"):
            self.assertEqual(gateway.validate_target(ip, 5555), ip + ":5555")
    def test_block_public_and_invalid(self):
        for ip in ("8.8.8.8", "github.com", "bad;rm -rf /", "", "256.1.1.1"):
            with self.assertRaises(ValueError): gateway.validate_target(ip, 5555)
        for p in (0, 65536, -1):
            with self.assertRaises(ValueError): gateway.validate_target("100.99.16.99", p)
    def test_serial_validation(self):
        self.assertEqual(gateway.safe_serial("100.99.16.99:5555"), "100.99.16.99:5555")
        with self.assertRaises(ValueError): gateway.safe_serial("x;shutdown")
    def test_mdns_parser(self):
        sample = """List of discovered mdns services
adb-R5CY40HFRPK-kaUWxW\t_adb-tls-connect._tcp\t192.168.1.74:38029
adb-other-foo\t_adb-tls-pairing._tcp\t100.99.16.99:42342
"""
        with patch.object(gateway, "run", return_value={"ok": True, "output": sample}):
            services = gateway.mdns_services()["services"]
        self.assertEqual(len(services), 2)
        self.assertEqual(services[0]["port"], 38029)
        self.assertEqual(services[1]["kind"], "pair")
    def test_device_parser(self):
        content = "List of devices attached\n100.85.113.35:5555 device product:OnePlus\n100.99.16.99:44537 offline\n"
        with patch.object(gateway, "run", return_value={"ok": True, "output": content}):
            devices = gateway.adb_devices()["devices"]
        self.assertEqual([d["state"] for d in devices], ["device", "offline"])
    def test_bearer(self):
        token = "secret-from-approved-client"
        fake = {"clients": {"one": hashlib.sha256(token.encode()).hexdigest()}}
        with patch.object(gateway, "identity", return_value=fake):
            self.assertTrue(gateway.token_ok("Bearer " + token))
            self.assertFalse(gateway.token_ok("Bearer wrong"))
            self.assertFalse(gateway.token_ok(""))

    def test_wireless_debugging_already_on(self):
        with patch.object(gateway, "run", return_value={"ok": True, "output": "1"}) as cmd:
            result = gateway.enable_wireless_debugging("100.99.16.99:45555")
        self.assertTrue(result["ok"])
        self.assertEqual(cmd.call_count, 1)  # no toggle when already enabled

    def test_wireless_debugging_authorized_toggle(self):
        with patch.object(gateway, "run", side_effect=[
            {"ok": True, "output": "0"},
            {"ok": True, "output": ""},
            {"ok": True, "output": "1"}]) as cmd:
            result = gateway.enable_wireless_debugging("100.99.16.99:45555")
        self.assertTrue(result["ok"])
        self.assertEqual(cmd.call_count, 3)
        self.assertIn("adb_wifi_enabled", cmd.call_args_list[1].args[0])

    def test_probe_endpoint_authenticated(self):
        import socket
        import threading
        from http.server import ThreadingHTTPServer
        from urllib.request import Request, urlopen
        import json
        upstream = socket.socket()
        upstream.bind(("127.0.0.1", 0))
        upstream.listen(1)
        server = ThreadingHTTPServer(("127.0.0.1", 0), gateway.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            data = json.dumps({"host": "127.0.0.1", "port": upstream.getsockname()[1]}).encode()
            req = Request(f"http://127.0.0.1:{server.server_port}/v1/probe",
                          data=data, method="POST",
                          headers={"Authorization": "Bearer test", "Content-Type": "application/json"})
            with patch.object(gateway, "token_ok", return_value=True):
                with urlopen(req, timeout=4) as response:
                    result = json.load(response)
            self.assertTrue(result["ok"], result)
        finally:
            server.shutdown()
            server.server_close()
            upstream.close()

    def test_discovery_inventory_without_enrollment(self):
        import json
        import threading
        from http.server import ThreadingHTTPServer
        from urllib.request import urlopen
        server = ThreadingHTTPServer(("127.0.0.1", 0), gateway.Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            fake = {"self":{"name":"host","ip":"100.70.1.1","online":True,"os":"linux"},
                    "peers":[{"name":"phone","ip":"100.80.1.2","online":True,"os":"android"}]}
            with patch.object(gateway, "tailnet", return_value=fake):
                with urlopen("http://127.0.0.1:%d/v1/discover" % server.server_port, timeout=4) as response:
                    body=json.load(response)
            self.assertTrue(body["ok"])
            self.assertEqual(body["peers"][0]["os"], "android")
        finally:
            server.shutdown(); server.server_close()

if __name__ == "__main__":
    unittest.main()
