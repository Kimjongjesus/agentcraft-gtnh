import unittest

from hermes_adapter.redact import REDACTED, WITHHELD, clean, clean_id


class RedactTest(unittest.TestCase):
    def test_vendor_tokens(self):
        # fake values, assembled at runtime so the source never contains a token-shaped literal
        for tok in (
            "sk-" + "ant-api03-" + "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "sk-" + "proj-" + "abcdefghijklmnop1234567890",
            "gh" + "p_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef1234",
            "github" + "_pat_" + "11ABCDEFG0123456789_abcdefghijklmnop",
            "xo" + "xb-" + "123456789012-abcdefghijkl",
            "AK" + "IA" + "ABCDEFGHIJKLMNOP",
            "gl" + "pat-" + "abcdefghijklmnop1234",
            "MTA4NzY1NDMyMTA5ODc2NTQzMg" + "." + "GhIjKl" + "." + "AbCdEfGhIjKlMnOpQrStUvWxYz012345678",
            "eyJ" + "hbGciOiJIUzI1NiJ9" + ".eyJ" + "zdWIiOiIxMjM0NTY3ODkwIn0" + "." + "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U",
        ):
            out = clean(f"key is {tok} ok")
            self.assertNotIn(tok, out, tok)
            self.assertIn(REDACTED, out)

    def test_key_value_and_headers(self):
        out = clean('password=hunter2 api_key: "abc def" token=xyz Authorization: Bearer abcdefghijklmnop123')
        for s in ("hunter2", "abc def", "xyz", "abcdefghijklmnop123"):
            self.assertNotIn(s, out)
        self.assertIn("password=", out)

    def test_url_credentials(self):
        out = clean("clone https://alice:s3cr3t@git.example.com/repo.git")
        self.assertNotIn("s3cr3t", out)
        self.assertNotIn("alice:", out)
        self.assertIn("https://", out)

    def test_private_key_block(self):
        out = clean("-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAB3NzaC1yc2EAAAADAQABAAABAQ\n-----END OPENSSH PRIVATE KEY-----", keep_newlines=True)
        self.assertEqual(out, REDACTED)

    def test_long_blobs(self):
        self.assertIn(REDACTED, clean("blob 0123456789abcdef0123456789abcdef01"))
        self.assertIn(REDACTED, clean("blob Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MEFCQ0RFRkdISUpLTE1O"))
        slug = "agentcraft-gtnh-port-card-1-toolchain-hermes-adapter"
        self.assertEqual(clean(slug), slug)

    def test_personal_notes_withheld(self):
        self.assertEqual(clean("see personal-schedule.md for times"), WITHHELD)
        self.assertEqual(clean("~/.claude/projects/-home-user/memory/personal-*.md"), WITHHELD)
        self.assertEqual(clean("x memory/personal stuff"), WITHHELD)

    def test_paths(self):
        self.assertEqual(clean("edit /home/user/project/scripts/tools/flow.py now"), "edit .../flow.py now")
        self.assertEqual(clean("read ~/dev-copy/server/server.properties"), "read .../server.properties")
        self.assertEqual(clean("cat /home/user/.config/tool/auth.json"), "cat [path]")
        self.assertEqual(clean("source /srv/app/.env"), "source [path]")
        self.assertEqual(clean("editing src/cli.ts"), "editing src/cli.ts")
        self.assertEqual(clean("see https://github.com/x/y"), "see https://github.com/x/y")
        self.assertEqual(clean("/answer d1 2"), "/answer d1 2")

    def test_email_whitespace_truncate(self):
        self.assertEqual(clean("mail owner@example.com"), "mail [email]")
        self.assertEqual(clean("a\n\tb   c"), "a b c")
        out = clean("x" * 100, limit=10)
        self.assertEqual(len(out), 10)
        self.assertTrue(out.endswith("\u2026"))
        self.assertEqual(clean(None), "")

    def test_clean_id(self):
        self.assertEqual(clean_id("Builder-A"), "builder-a")
        self.assertEqual(clean_id("a b/c"), "a-b-c")
        self.assertEqual(clean_id(""), "unknown")


if __name__ == "__main__":
    unittest.main()
