"""Original synthetic fixtures, assembled from bytes without Java or real packs."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import struct
import tempfile
import zipfile

from tools.pack_kb import core, extract

REPO = Path(__file__).resolve().parents[3]
SAMPLE = REPO / "tools/pack_kb/sample"


def scratch():
    """Never silently fall back to the system temporary directory."""
    root = os.environ.get("TMPDIR")
    if not root:
        raise RuntimeError("Set TMPDIR to an agent scratch directory before running tests")
    return tempfile.TemporaryDirectory(prefix="synthetic-pack-kb-", dir=root)


def store():
    return core.load_store(SAMPLE / "store.json")


def stack(item, amount=1, unit="item", **flags):
    return {"item": item, "amount": amount, "unit": unit, **flags}


def recipe(identifier, inputs, outputs, **fields):
    data = store()
    return {"id": identifier, "machine": "sample:bench", "inputs": inputs,
            "outputs": outputs, "duration_ticks": 20, "eut": 7, "enabled": True,
            "basis": "runtime-dump", "provenance": copy.deepcopy(data["recipes"][0]["provenance"]), **fields}


def custom(recipes):
    data = store()
    data["recipes"] = recipes
    return data


def u2(n):
    return struct.pack(">H", n)


def u4(n):
    return struct.pack(">I", n)


class SyntheticClass:
    """Small class-file assembler for decoder tests, not a JVM/compiler substitute."""
    def __init__(self, name):
        self.pool = [None]
        self.cache = {}
        self.name = self.cls(name)
        self.super = self.cls("java/lang/Object")
        self.fields = []
        self.methods = []

    def add(self, key, encoded, wide=False):
        if key not in self.cache:
            self.cache[key] = len(self.pool)
            self.pool.append(encoded)
            if wide:
                self.pool.append(None)
        return self.cache[key]

    def utf(self, text):
        encoded = text.encode("utf-8")
        return self.add(("utf", text), b"\x01" + u2(len(encoded)) + encoded)

    def cls(self, name):
        return self.add(("class", name), b"\x07" + u2(self.utf(name)))

    def string(self, value):
        return self.add(("string", value), b"\x08" + u2(self.utf(value)))

    def long(self, value):
        return self.add(("long", value), b"\x05" + struct.pack(">q", value), wide=True)

    def ref(self, owner, name, desc, tag=9):
        nt = self.add(("nt", name, desc), b"\x0c" + u2(self.utf(name)) + u2(self.utf(desc)))
        return self.add(("ref", tag, owner, name, desc), bytes([tag]) + u2(self.cls(owner)) + u2(nt))

    def opref(self, opcode, owner, name, desc):
        tag = 9 if opcode in (0xb2, 0xb3) else 11 if opcode == 0xb9 else 10
        result = bytes([opcode]) + u2(self.ref(owner, name, desc, tag))
        if opcode == 0xb9:
            # All synthetic interface calls here have no explicit arguments.
            result += b"\x01\x00"
        return result

    def field(self, name, desc):
        self.fields.append(u2(0x0009) + u2(self.utf(name)) + u2(self.utf(desc)) + u2(0))

    def method(self, name, desc, code, exceptions=0):
        payload = u2(64) + u2(4) + u4(len(code)) + code + u2(exceptions)
        payload += b"\x00" * (8 * exceptions) + u2(0)
        attr = u2(self.utf("Code")) + u4(len(payload)) + payload
        self.methods.append(u2(0x0009) + u2(self.utf(name)) + u2(self.utf(desc)) + u2(1) + attr)

    def build(self):
        return (b"\xca\xfe\xba\xbe" + u2(0) + u2(52) + u2(len(self.pool))
                + b"".join(value for value in self.pool[1:] if value is not None)
                + u2(0x0021) + u2(self.name) + u2(self.super) + u2(0)
                + u2(len(self.fields)) + b"".join(self.fields)
                + u2(len(self.methods)) + b"".join(self.methods) + u2(0))


def small_int(value):
    if -1 <= value <= 5:
        return bytes([value + 3])
    if -128 <= value <= 127:
        return b"\x10" + struct.pack(">b", value)
    return b"\x11" + struct.pack(">h", value)


def voltage_class():
    cf = SyntheticClass(extract.GT)
    code = b""
    for name, values in (("V", [11, 43]), ("VP", [7, 29]), ("VN", ["sample:T0", "sample:T1"])):
        string = name == "VN"
        desc = "[Ljava/lang/String;" if string else "[J"
        cf.field(name, desc)
        code += small_int(len(values))
        code += b"\xbd" + u2(cf.cls("java/lang/String")) if string else b"\xbc\x0b"
        for index, value in enumerate(values):
            code += b"\x59" + small_int(index)
            code += b"\x13" + u2(cf.string(value)) if string else b"\x14" + u2(cf.long(value))
            code += b"\x53" if string else b"\x50"
        code += cf.opref(0xb3, extract.GT, name, desc)
    cf.method("<clinit>", "()V", code + b"\xb1")
    return cf.build()


def tier_class():
    cf = SyntheticClass(extract.TIER)
    cf.field("RECIPE_SYNTHETIC", "J")
    code = cf.opref(0xb2, extract.GT, "VP", "[J") + small_int(1) + b"\x2f"
    code += cf.opref(0xb3, extract.TIER, "RECIPE_SYNTHETIC", "J") + b"\xb1"
    cf.method("<clinit>", "()V", code)
    return cf.build()


def loader_class(*, prefix=b"", suffix=b"", exceptions=0, unsupported=False):
    """One supported symbolic recipe: item + fluid + reusable configured circuit."""
    cf = SyntheticClass("gregtech/loaders/postload/recipes/SyntheticRecipes")
    code = cf.opref(0xb2, extract.GT, "RA", "L" + extract.ADDER + ";")
    code += cf.opref(0xb9, extract.ADDER, "stdBuilder", "()" + extract.BTYPE)
    for setter, field, amount in (("itemInputs", "SyntheticInput", 2), ("itemOutputs", "SyntheticOutput", 3)):
        code += small_int(1) + b"\xbd" + u2(cf.cls(extract.ITEM[1:-1])) + b"\x59\x03"
        code += cf.opref(0xb2, extract.ITEMLIST, field, "L" + extract.ITEMLIST + ";")
        code += b"\x14" + u2(cf.long(amount)) + b"\x03\xbd" + u2(cf.cls("java/lang/Object"))
        code += cf.opref(0xb6, extract.ITEMLIST, "get", "(J[Ljava/lang/Object;)" + extract.ITEM) + b"\x53"
        code += cf.opref(0xb6, extract.BUILDER, setter, "([" + extract.ITEM + ")" + extract.BTYPE)
    code += b"\x04\xbd" + u2(cf.cls(extract.FLUID[1:-1])) + b"\x59\x03"
    code += cf.opref(0xb2, extract.MATERIAL, "SyntheticLiquid", "L" + extract.MATERIAL + ";")
    code += b"\x14" + u2(cf.long(125))
    code += cf.opref(0xb6, extract.MATERIAL, "getFluid", "(J)" + extract.FLUID) + b"\x53"
    code += cf.opref(0xb6, extract.BUILDER, "fluidInputs", "([" + extract.FLUID + ")" + extract.BTYPE)
    code += small_int(4) + cf.opref(0xb6, extract.BUILDER, "circuit", "(I)" + extract.BTYPE)
    code += b"\x04\xbc\x0a\x59\x03" + small_int(2500) + b"\x4f"
    code += cf.opref(0xb6, extract.BUILDER, "outputChances", "([I)" + extract.BTYPE)
    code += small_int(37) + cf.opref(0xb6, extract.BUILDER, "duration", "(I)" + extract.BTYPE)
    code += cf.opref(0xb2, extract.TIER, "RECIPE_SYNTHETIC", "J")
    code += cf.opref(0xb6, extract.BUILDER, "eut", "(J)" + extract.BTYPE)
    if unsupported:
        code += cf.opref(0xb6, extract.BUILDER, "unmodeledMetadata", "()" + extract.BTYPE)
    code += cf.opref(0xb2, extract.MAPS, "syntheticRecipes", "Lgregtech/api/recipe/RecipeMap;")
    code += cf.opref(0xb6, extract.BUILDER, "addTo", "(Lgregtech/api/interfaces/IRecipeMap;)Ljava/util/Collection;") + b"\x57"
    cf.method("run", "()V", prefix + code + suffix + b"\xb1", exceptions)
    return cf.build()


LANGUAGE = b"# Original fictional language metadata\ngt.blockmachines.basicmachine.synthetic.tier.0.name=Fictional Press\ngt.blockmachines.synthetic.manual.name=Fictional Bench\n"
CONFIG = b"# Original fictional config\nsynthetic {\n B:enabled=true\n I:parallel=3\n D:ratio=0.75\n S:label=Fictional\n S:omitted <\n one\n >\n I:invalid=not-a-number\n}\n"


def make_pack(root, **loader_options):
    """Return exact original bytes by pack-relative source path for hash checks."""
    root = Path(root)
    (root / "mods").mkdir(parents=True, exist_ok=True)
    config = root / "config/GregTech/MachineStats.cfg"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_bytes(CONFIG)
    members = {extract.GT + ".class": voltage_class(), extract.TIER + ".class": tier_class(),
               "assets/gregtech/lang/en_US.lang": LANGUAGE,
               "gregtech/loaders/postload/recipes/SyntheticRecipes.class": loader_class(**loader_options)}
    with zipfile.ZipFile(root / "mods/gregtech-synthetic.jar", "w", compression=zipfile.ZIP_STORED) as jar:
        for name, payload in members.items():
            jar.writestr(name, payload)
    return {"config/GregTech/MachineStats.cfg": CONFIG,
            **{"mods/gregtech-synthetic.jar!/" + name: payload for name, payload in members.items()}}
