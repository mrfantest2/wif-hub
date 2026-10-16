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

if __name__ == "__main__":
    unittest.main()
