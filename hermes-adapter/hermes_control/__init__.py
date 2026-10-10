"""Hermes-side control service for the AgentCraft write path (card 7).

Implements the wire contract in docs/action-protocol.md. Python standard library only (plus the
read adapter's WebSocket server and privacy filter). Nothing here is imported by hermes_adapter:
the read adapter stays read-only and keeps refusing every ``action.*`` message.
"""

__version__ = "0.1.0"
PROTOCOL_VERSION = 1
