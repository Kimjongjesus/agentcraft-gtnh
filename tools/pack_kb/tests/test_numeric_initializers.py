"""Fail-closed numeric and initializer regressions; all class/jar bytes are synthetic.

No JVM, network, real pack, or on-disk synthetic archive is needed. These tests
exercise complete builder registrations, not just individual invoke() calls.
"""
from io import BytesIO
from pathlib import Path
import struct
import unittest
import zipfile

from tools.pack_kb import extract
from tools.pack_kb.classfile import ClassError, ClassFile
from tools.pack_kb.tests.helpers import SyntheticClass, small_int, u2, u4


INT_MAX = 2**31 - 1
NARROW_TO_ONE = 4294967297
LONG_MAX = 2**63 - 1
ARRAY_FIELDS = ("V", "VP", "VN")
ARRAY_VALUES = {"V": [11, 43], "VP": [7, 29],
                "VN": ["sample:T0", "sample:T1"]}


class NumericClass(SyntheticClass):
    """Local extension for int constants and real invokedynamic bootstrap data."""
    def __init__(self, name):
        super().__init__(name)
        self.attributes = []

    def number(self, value, kind="J"):
        if kind == "J":
            return b"\x14" + u2(self.long(value))
        if -32768 <= value <= 32767:
            return small_int(value)
        index = self.add(("int", value), b"\x03" + struct.pack(">i", value))
        return b"\x13" + u2(index)

    def mapped_assignment(self, source="V", target="VP"):
        """Arrays.stream(source).map(v -> BigInteger(v) / 2).toArray()."""
        big = "java/math/BigInteger"
        body = b"\x1e" + self.opref(0xb8, big, "valueOf", "(J)Ljava/math/BigInteger;")
        body += self.number(2) + self.opref(0xb8, big, "valueOf", "(J)Ljava/math/BigInteger;")
        body += self.opref(0xb6, big, "divide", "(Ljava/math/BigInteger;)Ljava/math/BigInteger;")
        body += self.opref(0xb6, big, "longValueExact", "()J") + b"\xad"
        if not any(key == ("utf", "lambda$voltage$0") for key in self.cache):
            self.method("lambda$voltage$0", "(J)J", body)
        factory_desc = ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                        "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                        "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        "Ljava/lang/invoke/CallSite;")
        factory = self.ref("java/lang/invoke/LambdaMetafactory", "metafactory", factory_desc, 10)
        handle = self.add(("factory_handle",), b"\x0f\x06" + u2(factory))
        target_ref = self.ref(extract.GT, "lambda$voltage$0", "(J)J", 10)
        target_handle = self.add(("target_handle",), b"\x0f\x06" + u2(target_ref))
        method_type = self.add(("method_type", "(J)J"), b"\x10" + u2(self.utf("(J)J")))
        if not self.attributes:
            payload = u2(1) + u2(handle) + u2(3)
            payload += u2(method_type) + u2(target_handle) + u2(method_type)
            self.attributes.append(u2(self.utf("BootstrapMethods")) + u4(len(payload)) + payload)
        nt = self.add(("dynamic_nt",), b"\x0c" + u2(self.utf("applyAsLong"))
                      + u2(self.utf("()Ljava/util/function/LongUnaryOperator;")))
        dynamic = self.add(("dynamic",), b"\x12" + u2(0) + u2(nt))
        code = self.opref(0xb2, extract.GT, source, "[J")
        code += self.opref(0xb8, "java/util/Arrays", "stream", "([J)Ljava/util/stream/LongStream;")
        code += b"\xba" + u2(dynamic) + b"\x00\x00"
        # map has one argument and its receiver occupies the other JVM slot.
        code += self.opref(0xb9, "java/util/stream/LongStream", "map",
                           "(Ljava/util/function/LongUnaryOperator;)Ljava/util/stream/LongStream;")[:-2] + b"\x02\x00"
        code += self.opref(0xb9, "java/util/stream/LongStream", "toArray", "()[J")
        return code + self.opref(0xb3, extract.GT, target, "[J")

    def build(self):
        return super().build()[:-2] + u2(len(self.attributes)) + b"".join(self.attributes)


def array_assignment(cf, field, values=None):
    values = ARRAY_VALUES[field] if values is None else values
    string = field == "VN"
    descriptor = "[Ljava/lang/String;" if string else "[J"
    code = small_int(len(values))
    code += b"\xbd" + u2(cf.cls("java/lang/String")) if string else b"\xbc\x0b"
    for index, value in enumerate(values):
        code += b"\x59" + small_int(index)
        code += b"\x13" + u2(cf.string(value)) if string else cf.number(value)
        code += b"\x53" if string else b"\x50"
    return code + cf.opref(0xb3, extract.GT, field, descriptor)


def unsupported_array_assignment(cf, field):
    descriptor = "[Ljava/lang/String;" if field == "VN" else "[J"
    # Valid JVM shape, but the value requires an unmodelled helper call.
    return (cf.opref(0xb8, "synthetic/Unknown", "array", "()" + descriptor)
            + cf.opref(0xb3, extract.GT, field, descriptor))


def conditional(cf, body):
    # An unknown condition may skip the whole assignment body.
    flag = cf.opref(0xb2, "synthetic/Conditions", "flag", "Z")
    return flag + b"\x99" + struct.pack(">h", len(body) + 3) + body


def voltage_fixture(*, field=None, mutation=None, mapped=False):
    cf = NumericClass(extract.GT)
    for name in ARRAY_FIELDS:
        cf.field(name, "[Ljava/lang/String;" if name == "VN" else "[J")
    parts = []
    if mutation == "source_read_before_initialization":
        parts.append(cf.mapped_assignment())
    for name in ARRAY_FIELDS:
        if name == "VP" and (mapped or mutation == "source_read_before_initialization"):
            body = cf.mapped_assignment() if mutation != "source_read_before_initialization" else b""
        else:
            values = [14, 58] if mapped and name == "V" else None
            body = array_assignment(cf, name, values)
        if name == field and mutation == "conditional":
            body = conditional(cf, body)
        parts.append(body)
    if mutation in ("duplicate", "unsupported_after", "conditional_after"):
        if mutation == "duplicate":
            replacement = ["sample:Changed0", "sample:Changed1"] if field == "VN" else [101, 203]
            body = array_assignment(cf, field, replacement)
        else:
            body = unsupported_array_assignment(cf, field)
        parts.append(conditional(cf, body) if mutation == "conditional_after" else body)
    if mutation == "duplicate_mapped":
        parts.append(cf.mapped_assignment())
    if mutation == "literal_after_mapped":
        parts.append(array_assignment(cf, "VP", [101, 203]))
    cf.method("<clinit>", "()V", b"".join(parts) + b"\xb1")
    return cf.build()


def tier_fixture(mutation=None, field="RECIPE_T0"):
    cf = NumericClass(extract.TIER)
    cf.field(field, "J")
    body = cf.opref(0xb2, extract.GT, "VP", "[J") + small_int(0) + b"\x2f"
    body += cf.opref(0xb3, extract.TIER, field, "J")
    if mutation == "conditional":
        body = conditional(cf, body)
    elif mutation == "duplicate":
        body += cf.opref(0xb2, extract.GT, "V", "[J") + small_int(1) + b"\x2f"
        body += cf.opref(0xb3, extract.TIER, field, "J")
    elif mutation in ("unsupported_after", "conditional_after"):
        replacement = cf.opref(0xb8, "synthetic/Unknown", "voltage", "()J")
        replacement += cf.opref(0xb3, extract.TIER, field, "J")
        body += conditional(cf, replacement) if mutation == "conditional_after" else replacement
    cf.method("<clinit>", "()V", body + b"\xb1")
    return cf.build()


def stack_expression(cf, kind, amount, *, ore_object=False, getter="getFluid", output=False):
    if kind == "itemlist":
        field = "SyntheticOutput" if output else "SyntheticInput"
        code = cf.opref(0xb2, extract.ITEMLIST, field, "L" + extract.ITEMLIST + ";")
        code += cf.number(amount) + b"\x03\xbd" + u2(cf.cls("java/lang/Object"))
        return code + cf.opref(0xb6, extract.ITEMLIST, "get", "(J[Ljava/lang/Object;)" + extract.ITEM)
    if kind == "ore":
        code = cf.opref(0xb2, extract.PREFIX, "dust", "L" + extract.PREFIX + ";")
        code += cf.opref(0xb2, extract.MATERIAL, "SyntheticMetal", "L" + extract.MATERIAL + ";")
        code += cf.number(amount)
        material = "Ljava/lang/Object;" if ore_object else "L" + extract.MATERIAL + ";"
        return code + cf.opref(0xb8, "gregtech/api/util/GTOreDictUnificator", "get",
                              "(L" + extract.PREFIX + ";" + material + "J)" + extract.ITEM)
    code = cf.opref(0xb2, extract.MATERIAL, "SyntheticLiquid", "L" + extract.MATERIAL + ";")
    return code + cf.number(amount) + cf.opref(0xb6, extract.MATERIAL, getter, "(J)" + extract.FLUID)


def loader_fixture(*, kind="itemlist", amount=2, duration=37, eut=29,
                   duration_type="I", eut_type="J", eut_field=None, **stack_options):
    cf = NumericClass("gregtech/loaders/postload/recipes/NumericRecipes")
    code = cf.opref(0xb2, extract.GT, "RA", "L" + extract.ADDER + ";")
    code += cf.opref(0xb9, extract.ADDER, "stdBuilder", "()" + extract.BTYPE)
    for setter, stack_kind, stack_amount, options in (
            ("fluidInputs" if kind == "fluid" else "itemInputs", kind, amount, stack_options),
            ("itemOutputs", "itemlist", 3, {"output": True})):
        descriptor = extract.FLUID if stack_kind == "fluid" else extract.ITEM
        code += b"\x04\xbd" + u2(cf.cls(descriptor[1:-1])) + b"\x59\x03"
        code += stack_expression(cf, stack_kind, stack_amount, **options) + b"\x53"
        code += cf.opref(0xb6, extract.BUILDER, setter, "([" + descriptor + ")" + extract.BTYPE)
    code += cf.number(duration, duration_type)
    code += cf.opref(0xb6, extract.BUILDER, "duration", "(" + duration_type + ")" + extract.BTYPE)
    code += cf.opref(0xb2, *eut_field) if eut_field else cf.number(eut, eut_type)
    code += cf.opref(0xb6, extract.BUILDER, "eut", "(" + eut_type + ")" + extract.BTYPE)
    code += cf.opref(0xb2, extract.MAPS, "numericRecipes", "Lgregtech/api/recipe/RecipeMap;")
    code += cf.opref(0xb6, extract.BUILDER, "addTo", "(Lgregtech/api/interfaces/IRecipeMap;)Ljava/util/Collection;")
    cf.method("run", "()V", code + b"\x57\xb1")
    return cf.build()


def engine():
    # Root is only validated; synthetic classes and zip members stay in memory.
    result = extract.Extractor(Path(__file__).resolve().parents[3], "synthetic-numeric")
    result.data["coverage"]["static_builder_candidates"] = 0
    return result


def read_tiers(result, voltage, tier=None):
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr(extract.GT + ".class", voltage)
        if tier is not None:
            archive.writestr(extract.TIER + ".class", tier)
    buffer.seek(0)
    with zipfile.ZipFile(buffer) as archive:
        result.tiers(archive, "mods/gregtech-synthetic.jar")


class NumericConversionTests(unittest.TestCase):
    def registration(self, **options):
        result = engine()
        payload = loader_fixture(**options)
        sid = result.source("mods/gregtech-synthetic.jar!/NumericRecipes.class", payload)
        result.recipes(payload, sid)
        self.assertEqual(result.data["coverage"]["static_builder_candidates"], 1)
        return result

    def assert_omitted(self, **options):
        result = self.registration(**options)
        self.assertEqual(result.data["recipes"], [], options)
        self.assertTrue(result.skips, "Unsupported numeric registration should be counted")

    def test_normal_complete_builder_preserves_identity_units_and_scalars(self):
        for kind, identity, amount, unit in (
                ("itemlist", "gt:ItemList:SyntheticInput", 2, "item"),
                ("ore", "ore:dust:SyntheticMetal", 2, "item"),
                ("fluid", "gt:Materials:SyntheticLiquid:getFluid", 125, "mB")):
            with self.subTest(kind=kind):
                result = self.registration(kind=kind, amount=amount)
                self.assertEqual(result.skips, {})
                self.assertEqual(len(result.data["recipes"]), 1)
                recipe = result.data["recipes"][0]
                self.assertEqual(recipe["inputs"], [{"item": identity, "amount": amount, "unit": unit}])
                self.assertEqual(recipe["outputs"], [{"item": "gt:ItemList:SyntheticOutput", "amount": 3, "unit": "item"}])
                self.assertEqual((recipe["duration_ticks"], recipe["eut"], recipe["machine"]), (37, 29, "numericRecipes"))

    def test_itemlist_identity_amount_boundaries(self):
        for amount in (0, 64):
            with self.subTest(amount=amount):
                result = self.registration(amount=amount)
                self.assertEqual(len(result.data["recipes"]), 1)
                expected = {"item": "gt:ItemList:SyntheticInput", "amount": max(1, amount), "unit": "item"}
                if amount == 0:
                    expected["consumed"] = False
                self.assertEqual(result.data["recipes"][0]["inputs"], [expected])
        for amount in (-1, 65, 128, INT_MAX + 1, NARROW_TO_ONE, LONG_MAX):
            with self.subTest(amount=amount):
                self.assert_omitted(amount=amount)

    def test_ore_long_amount_identity_bounds_include_copyamount_clamping(self):
        for ore_object in (False, True):
            for amount in (1, 2, 64):
                with self.subTest(ore_object=ore_object, amount=amount):
                    result = self.registration(kind="ore", amount=amount, ore_object=ore_object)
                    self.assertEqual(len(result.data["recipes"]), 1)
                    self.assertEqual(result.data["recipes"][0]["inputs"], [{
                        "item": "ore:dust:SyntheticMetal", "amount": amount, "unit": "item"}])
            # Unificator rejects zero and copyAmount clamps values above 64;
            # publishing the literal long would not preserve the runtime amount.
            for amount in (-1, 0, 65, 128, INT_MAX, INT_MAX + 1, NARROW_TO_ONE, LONG_MAX):
                with self.subTest(ore_object=ore_object, amount=amount):
                    self.assert_omitted(kind="ore", amount=amount, ore_object=ore_object)

    def test_material_fluid_long_amount_boundaries_for_every_getter(self):
        for getter in ("getFluid", "getGas", "getMolten", "getPlasma"):
            for amount in (64, 65, 128, INT_MAX):
                with self.subTest(getter=getter, amount=amount):
                    result = self.registration(kind="fluid", amount=amount, getter=getter)
                    self.assertEqual(len(result.data["recipes"]), 1)
                    self.assertEqual(result.data["recipes"][0]["inputs"], [{
                        "item": "gt:Materials:SyntheticLiquid:" + getter, "amount": amount, "unit": "mB"}])
            for amount in (-1, 0, INT_MAX + 1, NARROW_TO_ONE, LONG_MAX):
                with self.subTest(getter=getter, amount=amount):
                    self.assert_omitted(kind="fluid", amount=amount, getter=getter)

    def test_duration_and_eut_int_and_long_nonnegative_int32_boundaries(self):
        for field in ("duration", "eut"):
            for kind in ("I", "J"):
                for value in (0, 64, 65, 128, INT_MAX):
                    with self.subTest(field=field, descriptor=kind, value=value):
                        result = self.registration(**{field: value, field + "_type": kind})
                        self.assertEqual(len(result.data["recipes"]), 1)
                        key = "duration_ticks" if field == "duration" else "eut"
                        self.assertEqual(result.data["recipes"][0][key], value)
                with self.subTest(field=field, descriptor=kind, value=-1):
                    self.assert_omitted(**{field: -1, field + "_type": kind})
            for value in (INT_MAX + 1, NARROW_TO_ONE, LONG_MAX):
                with self.subTest(field=field, descriptor="J", value=value):
                    self.assert_omitted(**{field: value, field + "_type": "J"})


class AmbiguousInitializerTests(unittest.TestCase):
    def read_ambiguous(self, voltage, tier=None):
        result = engine()
        try:
            read_tiers(result, voltage, tier)
        except (extract.ExtractionError, extract.Unsupported, ClassError):
            # Explicit rejection is acceptable, but partial/stale publication is not.
            pass
        return result

    def assert_array_absent(self, result, field):
        descriptor = "[Ljava/lang/String;" if field == "VN" else "[J"
        self.assertNotIn((extract.GT, field, descriptor), result.constants)
        self.assertNotIn(field, result.data["coverage"].get("voltage_arrays", []))
        self.assertEqual(result.data["tiers"], [], "Ambiguous tier arrays must never publish stale records")

    def assert_tier_absent(self, result):
        self.assertNotIn((extract.TIER, "RECIPE_T0", "J"), result.constants)
        for tier in result.data["tiers"]:
            self.assertFalse(any("field:RECIPE_T0" in p["locator"] for p in tier["provenance"]),
                             "A rejected assignment must not remain in published tier provenance")
        payload = loader_fixture(eut_field=(extract.TIER, "RECIPE_T0", "J"))
        sid = result.source("mods/gregtech-synthetic.jar!/DependentRecipes.class", payload)
        result.recipes(payload, sid)
        self.assertEqual(result.data["recipes"], [], "A recipe must not consume a stale TierEU constant")

    def test_normal_literal_arrays_and_tier_dependency_still_extract(self):
        result = engine()
        read_tiers(result, voltage_fixture(), tier_fixture())
        self.assertEqual([(row["id"], row["voltage"], row["recipe_eut"]) for row in result.data["tiers"]],
                         [("sample:T0", 11, 7), ("sample:T1", 43, 29)])
        self.assertEqual(result.constants[(extract.TIER, "RECIPE_T0", "J")][0], 7)
        payload = loader_fixture(eut_field=(extract.TIER, "RECIPE_T0", "J"))
        result.recipes(payload, result.source("mods/gregtech-synthetic.jar!/NumericRecipes.class", payload))
        self.assertEqual(len(result.data["recipes"]), 1)
        self.assertEqual(result.data["recipes"][0]["eut"], 7)

    def test_duplicate_literal_v_vp_vn_never_keep_first_assignment(self):
        for field in ARRAY_FIELDS:
            with self.subTest(field=field):
                payload = voltage_fixture(field=field, mutation="duplicate")
                try:
                    arrays = extract.literal_arrays(ClassFile(payload))
                except (extract.Unsupported, ClassError):
                    arrays = {}
                self.assertNotIn(field, arrays, "Duplicate assignment must invalidate previously accepted literal")
                self.assert_array_absent(self.read_ambiguous(payload), field)

    def test_unmodelled_assignment_after_literal_invalidates_v_vp_vn(self):
        for field in ARRAY_FIELDS:
            with self.subTest(field=field):
                result = self.read_ambiguous(voltage_fixture(field=field, mutation="unsupported_after"))
                self.assert_array_absent(result, field)

    def test_control_flow_dependent_array_initializers_are_omitted(self):
        for field in ARRAY_FIELDS:
            for mutation in ("conditional", "conditional_after"):
                with self.subTest(field=field, mutation=mutation):
                    result = self.read_ambiguous(voltage_fixture(field=field, mutation=mutation))
                    self.assert_array_absent(result, field)

    def test_normal_mapped_voltage_initializer_is_supported(self):
        result = engine()
        read_tiers(result, voltage_fixture(mapped=True), tier_fixture())
        self.assertEqual([(row["voltage"], row["recipe_eut"]) for row in result.data["tiers"]], [(14, 7), (58, 29)])
        self.assertEqual(result.constants[(extract.GT, "VP", "[J")][0], [7, 29])
        self.assertTrue(any("lambda$voltage$0" in p["locator"] for p in result.constants[(extract.GT, "VP", "[J")][1]))

    def test_mapped_voltage_invalidated_when_source_is_ambiguous(self):
        for mutation in ("duplicate", "unsupported_after", "conditional", "conditional_after"):
            with self.subTest(mutation=mutation):
                result = self.read_ambiguous(voltage_fixture(field="V", mutation=mutation, mapped=True), tier_fixture())
                self.assert_array_absent(result, "V")
                self.assertNotIn((extract.GT, "VP", "[J"), result.constants)
                self.assert_tier_absent(result)

    def test_mapped_voltage_reassignments_do_not_choose_literal_or_last_map(self):
        for mutation in ("duplicate_mapped", "literal_after_mapped", "unsupported_after", "conditional_after"):
            with self.subTest(mutation=mutation):
                result = self.read_ambiguous(voltage_fixture(field="VP", mutation=mutation, mapped=True), tier_fixture())
                self.assert_array_absent(result, "VP")
                self.assert_tier_absent(result)

    def test_mapping_cannot_read_source_before_its_initialization(self):
        result = self.read_ambiguous(voltage_fixture(mutation="source_read_before_initialization"), tier_fixture())
        self.assert_array_absent(result, "VP")
        self.assert_tier_absent(result)

    def test_tiereu_reassignment_or_conditional_assignment_invalidates_dependent_recipe(self):
        for mutation in ("duplicate", "unsupported_after", "conditional", "conditional_after"):
            with self.subTest(mutation=mutation):
                result = self.read_ambiguous(voltage_fixture(), tier_fixture(mutation))
                self.assert_tier_absent(result)

    def test_unresolved_matching_tiereu_field_removes_array_fallback_record(self):
        for mutation in ("duplicate", "unsupported_after", "conditional", "conditional_after"):
            with self.subTest(mutation=mutation):
                result = self.read_ambiguous(
                    voltage_fixture(), tier_fixture(mutation, field="RECIPE_sample:T0"))
                self.assertEqual([tier["id"] for tier in result.data["tiers"]], ["sample:T1"])
                self.assertNotIn((extract.TIER, "RECIPE_sample:T0", "J"), result.constants)
                self.assertTrue(any("Unresolved TierEU" in warning
                                    for warning in result.data["coverage"]["warnings"]))

    def test_closed_loop_before_initializers_preserves_unconditional_arrays(self):
        cf = NumericClass(extract.GT)
        # An unrelated loop may finish at the first array instruction. Its
        # back edge does not enter or repeat the array construction itself.
        loop = (cf.opref(0xb2, "synthetic/Conditions", "flag", "Z")
                + b"\x99\x00\x06\xa7\xff\xfa")
        body = b""
        for field in ARRAY_FIELDS:
            cf.field(field, "[Ljava/lang/String;" if field == "VN" else "[J")
            body += array_assignment(cf, field)
        cf.method("<clinit>", "()V", loop + body + b"\xb1")
        result = engine()
        read_tiers(result, cf.build())
        self.assertEqual([(tier["voltage"], tier["recipe_eut"])
                          for tier in result.data["tiers"]], [(11, 7), (43, 29)])

    def test_write_in_another_method_invalidates_literal_initializer(self):
        cf = NumericClass(extract.GT)
        cf.field("V", "[J")
        cf.method("<clinit>", "()V", array_assignment(cf, "V") + b"\xb1")
        cf.method("reset", "()V", unsupported_array_assignment(cf, "V") + b"\xb1")
        self.assertNotIn("V", extract.literal_arrays(ClassFile(cf.build())))


if __name__ == "__main__":
    unittest.main()
