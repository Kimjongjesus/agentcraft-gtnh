"""Card 5a: IP-address redaction (default on, configurable) and credential URLs.

Every literal address below is either an RFC 5737 documentation address (192.0.2.x,
198.51.100.x, 203.0.113.x), an RFC 3849 documentation prefix (2001:db8::/32), loopback or
built at runtime; nothing here is a real host.
"""

import unittest

from hermes_adapter.redact import IP, REDACTED, clean, clean_excerpt, ip_policy, redact_ips, redact_ips_enabled


class IpRedactionTest(unittest.TestCase):
    def test_default_is_on(self):
        self.assertTrue(redact_ips_enabled())

    def test_ipv4(self):
        self.assertEqual(clean("host 192.0.2.15 is down"), f"host {IP} is down")
        self.assertEqual(clean("listening on 192.0.2.10:7878"), f"listening on {IP}:7878")
        self.assertEqual(clean("allow 198.51.100.0/24 only"), f"allow {IP}/24 only")
        self.assertEqual(clean("(203.0.113.7)"), f"({IP})")
        self.assertEqual(clean("a,192.0.2.1,b"), f"a,{IP},b")
        self.assertEqual(clean("ping 127.0.0.1"), f"ping {IP}")

    def test_ipv4_in_urls(self):
        self.assertEqual(clean("GET http://192.0.2.40:3001/metrics"), f"GET http://{IP}:3001/metrics")
        self.assertEqual(clean("see https://192.0.2.40/a/b/c"), f"see https://{IP}/a/b/c")

    def test_ipv6(self):
        self.assertEqual(clean("addr 2001:db8::1 up"), f"addr {IP} up")
        self.assertEqual(clean("addr 2001:db8:0:0:0:0:0:1"), f"addr {IP}")
        self.assertEqual(clean("connect [2001:db8::5]:443"), f"connect {IP}:443")
        self.assertEqual(clean("[::1]:7878"), f"{IP}:7878")
        self.assertEqual(clean("fe80::1%eth0 link"), f"{IP} link")
        self.assertEqual(clean("prefix 2001:db8::/32"), f"prefix {IP}/32")
        self.assertEqual(clean("mapped ::ffff:192.0.2.1"), f"mapped {IP}")

    def test_not_addresses(self):
        for s in (
            "GregTech 5.09.54.133",          # leading zero: not an address
            "forge 10.13.4.1614",           # octet > 255
            "v1.2.3.4",                     # glued to a word
            "1.2.3.4.5",                    # five parts
            "at 12:30:45 today",            # a time
            "mac aa:bb:cc:dd:ee:ff",        # a MAC address
            "std::vector and a::",          # C++ scope, no address
            "ratio 3:2",
            "uptime 3 days, 22:46",
            "2026-10-06T04:38:41+00:00",    # ISO timestamp
            "sha256:abcdef0123",
        ):
            self.assertEqual(clean(s), s, s)

    def test_config_allows_ips(self):
        text = "host 192.0.2.15 and 2001:db8::1"
        with ip_policy(False):
            self.assertFalse(redact_ips_enabled())
            self.assertEqual(clean(text), text)
        self.assertTrue(redact_ips_enabled())
        self.assertEqual(clean(text), f"host {IP} and {IP}")

    def test_allow_ips_never_allows_credentials(self):
        with ip_policy(False):
            out = clean("https://bob:hunter2@192.0.2.9/x?token=abc")
            self.assertNotIn("hunter2", out)
            self.assertNotIn("abc", out)
            self.assertIn("192.0.2.9", out)

    def test_redact_ips_helper_and_excerpt(self):
        self.assertEqual(redact_ips("a 192.0.2.1 b 2001:db8::2"), f"a {IP} b {IP}")
        # the whole source is filtered before a line is cut out
        self.assertEqual(clean_excerpt("PROGRESS: probing 192.0.2.3\nmore", 80), f"PROGRESS: probing {IP}")


class CredentialUrlTest(unittest.TestCase):
    def test_userinfo(self):
        out = clean("clone https://bob:s3cr3t@git.example.com/r.git")
        self.assertNotIn("s3cr3t", out)
        self.assertNotIn("bob", out)
        tok = "Ab" + "cdEFgh0123456789xyz"
        out = clean(f"push https://{tok}@git.example.com/r.git")
        self.assertNotIn(tok, out)
        self.assertIn(f"https://{REDACTED}@git.example.com", out)
        self.assertNotIn(REDACTED, clean("ssh://git@git.example.com/r.git"))  # a short user part is not a token

    def test_query_parameters(self):
        for q, secret in (
            ("?token=abc123", "abc123"),
            ("?access_token=zzz", "zzz"),
            ("?key=k3y", "k3y"),
            ("?sig=s1g&page=2", "s1g"),
            ("?X-Amz-Signature=deadbeef", "deadbeef"),
            ("?code=oauthcode", "oauthcode"),
            ("?client_secret=cs&x=1", "cs&"),
            ("?apikey=AK", "=AK"),
            ("&password=pw9", "pw9"),
        ):
            out = clean(f"open https://svc.example.com/cb{q}")
            self.assertNotIn(secret, out, q)
            self.assertIn(REDACTED, out, q)
        self.assertEqual(clean("https://svc.example.com/list?page=2&sort=asc"), "https://svc.example.com/list?page=2&sort=asc")
        self.assertEqual(clean("https://svc.example.com/zip?zipcode=12345"), "https://svc.example.com/zip?zipcode=12345")

    def test_webhooks(self):
        wid, wtok = "123456789012345678", "abcdefGHIJKL" + "mnop_qrstuv-wxyz0123456789ABCDEFGH"
        out = clean(f"post to https://discord.com/api/webhooks/{wid}/{wtok}")
        self.assertNotIn(wid, out)
        self.assertNotIn(wtok, out)
        self.assertIn("discord.com/api/webhooks/" + REDACTED, out)
        slack = "T000" + "/B000/XXXXXXXXXXXXXXXXXXXXXXXX"
        out = clean(f"slack https://hooks.slack.com/services/{slack}")
        self.assertNotIn("XXXXXXXX", out)

    def test_opaque_path_segments(self):
        tok = "123456:" + "AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"
        out = clean(f"https://api.telegram.org/bot{tok}/sendMessage")
        self.assertNotIn("AAHdqTcv", out)
        self.assertTrue(out.endswith("/sendMessage"), out)
        # readable slugs in a path survive
        url = "https://github.com/example/agentcraft-gtnh-port-card-1-toolchain/pull/12"
        self.assertEqual(clean(url), url)


if __name__ == "__main__":
    unittest.main()
