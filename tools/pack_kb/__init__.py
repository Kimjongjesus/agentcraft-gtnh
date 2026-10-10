"""Standard-library, read-only pack knowledge queries and recipe planning."""

from .core import (
    MAX_CHAINS,
    MAX_DEPTH,
    MAX_STORE_BYTES,
    SCHEMA_VERSION,
    StoreValidationError,
    ValidationError,
    load_store,
    machines_by_tier,
    needs,
    recipes_by_output,
    requirements,
    resolve,
    validate_store,
)

__all__ = [
    "SCHEMA_VERSION", "MAX_STORE_BYTES", "MAX_DEPTH", "MAX_CHAINS",
    "ValidationError", "StoreValidationError", "load_store", "validate_store",
    "recipes_by_output", "machines_by_tier", "needs", "requirements", "resolve",
]
