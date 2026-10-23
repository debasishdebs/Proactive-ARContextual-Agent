"""WP13: `schema_lite` (Python) gives the SAME verdicts as the Kotlin SchemaSubset on every shared row of protocol/schema-subset-rows.json, and the
copy shipped in the job image is byte-identical. The rows are loaded, never restated."""
import hashlib
from pathlib import Path

import pytest

import protocol_loader as L
from pca_helper import schema_lite

ROWS = L.load("schema-subset-rows.json")
REPO = Path(__file__).resolve().parents[3]


@pytest.mark.parametrize("row", ROWS["instanceRows"], ids=lambda r: r["id"])
def test_instance_rows(row):
    assert schema_lite.validate(row["schema"], row["instance"]) is row["valid"]


@pytest.mark.parametrize("row", ROWS["contractRows"], ids=lambda r: r["id"])
def test_contract_rows(row):
    assert (schema_lite.contract_errors(row["schema"]) == []) is row["supported"]


def test_every_row_is_run_and_the_constants_match_the_fixtures():
    assert len(ROWS["instanceRows"]) >= 30 and len(ROWS["contractRows"]) >= 20
    c = L.load("constants.json")
    assert schema_lite.MAX_COUNT == c["schemaSubsetMaxCount"] and schema_lite.MAX_INSTANCE_DEPTH == c["py"]["maxInstanceDepth"]
    assert set(schema_lite.KEYWORDS) == set(c["schemaSubsetKeywords"]) and set(schema_lite.TYPES) == set(c["schemaTypes"])


def test_booleans_are_not_numbers_and_enum_is_strict_except_numeric_equality():
    assert not schema_lite.validate({"type": "integer"}, True)
    assert not schema_lite.validate({"type": "number"}, False)
    assert not schema_lite.validate({"enum": [1]}, True)
    assert schema_lite.validate({"enum": [1]}, 1.0) and schema_lite.validate({"enum": [True]}, True)
    assert not schema_lite.validate({"type": "integer"}, 3.0), "an integer is an integer token: 3.0 is a float"
    assert not schema_lite.validate({"type": "number"}, float("nan")) and not schema_lite.validate({"type": "number"}, float("inf"))


def test_the_image_copy_is_byte_identical():
    a = (REPO / "tools" / "canvas-helper" / "pca_helper" / "schema_lite.py").read_bytes()
    b = (REPO / "container" / "pyjob" / "schema_lite.py").read_bytes()
    assert hashlib.sha256(a).hexdigest() == hashlib.sha256(b).hexdigest(), "copy container/pyjob/schema_lite.py from pca_helper/schema_lite.py"
