#!/usr/bin/env python3
"""Prints the HMAC-SHA256 test vector hard-coded in dev/tests/ProtoCheck.java.

key bytes 0x00..0x1f over the payload string {"a":1}; run it again after any change to the wire rules
and compare with the constant in ProtoCheck (the Java side must not compute its own expectation).
"""
import hashlib
import hmac

key = bytes(range(32))
payload = '{"a":1}'
print("key_hex", key.hex())
print("payload", payload)
print("hmac_sha256", hmac.new(key, payload.encode("utf-8"), hashlib.sha256).hexdigest())
