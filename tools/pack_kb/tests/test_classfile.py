"""Decoder boundaries using hand-assembled original class data only."""
import struct
import unittest
from unittest.mock import patch

from tools.pack_kb import classfile, extract
from tools.pack_kb.tests.helpers import SyntheticClass, voltage_class


class ClassfileTests(unittest.TestCase):
    def test_generated_class_fields_literal_arrays_and_member_references(self):
        cf = classfile.ClassFile(voltage_class())
        self.assertEqual(cf.name, extract.GT)
        self.assertEqual(cf.major, 52)
        self.assertEqual(cf.fields, {"V": "[J", "VP": "[J", "VN": "[Ljava/lang/String;"})
        arrays = extract.literal_arrays(cf)
        self.assertEqual(arrays["V"][0], [11, 43])
        self.assertEqual(arrays["VP"][0], [7, 29])
        self.assertEqual(arrays["VN"][0], ["sample:T0", "sample:T1"])
        self.assertTrue(all(type(pc) is int for pc in arrays["VN"][1]))
        for index in (0, len(cf.pool), -1):
            with self.assertRaises(classfile.ClassError):
                cf.entry(index)
        wide_hole = next(i for i, value in enumerate(cf.pool) if i and value is None)
        with self.assertRaises(classfile.ClassError):
            cf.entry(wide_hole)
        class_index = next(i for i, value in enumerate(cf.pool) if value and value[0] == 7)
        with self.assertRaises(classfile.ClassError):
            cf.ref(class_index)
        with self.assertRaises(classfile.ClassError):
            cf.literal(class_index)

    def test_class_rejects_bad_magic_truncation_trailing_and_size(self):
        payload = voltage_class()
        for bad in (b"", b"bad!", b"\0" * 10, payload[:-1], payload + b"trailing"):
            with self.subTest(length=len(bad)), self.assertRaises(classfile.ClassError):
                classfile.ClassFile(bad)
        with patch.object(classfile, "MAX_CLASS", 1), self.assertRaisesRegex(classfile.ClassError, "size bound"):
            classfile.ClassFile(payload)
        # Unsupported first pool tag, retaining a well-formed file header.
        with self.assertRaisesRegex(classfile.ClassError, "constant-pool tag"):
            classfile.ClassFile(payload[:10] + b"\xff" + payload[11:])

    def test_reader_bounds_and_instruction_offsets(self):
        reader = classfile.Reader(b"\x00\x02")
        self.assertEqual(reader.u2(), 2)
        with self.assertRaises(classfile.ClassError):
            reader.u1()
        with self.assertRaises(classfile.ClassError):
            classfile.Reader(b"abc").take(-1)
        self.assertEqual(list(classfile.instructions(b"\x03\x10\x07\x11\x01\x00\xb1")), [(0, 3, b""), (1, 0x10, b"\x07"), (3, 0x11, b"\x01\x00"), (6, 0xb1, b"")])
        for code in (b"\x11\x01", b"\xff", b"\xc4\x00", b"\xc4\x15\x00"):
            with self.subTest(code=code), self.assertRaises(classfile.ClassError):
                list(classfile.instructions(code))
        self.assertEqual(list(classfile.instructions(b"\xc4\x84\x00\x01\x00\x02"))[0][1], 0xc4)

    def test_switch_sizes_alignment_and_truncation(self):
        table = b"\xaa\0\0\0" + struct.pack(">iiii", 0, 1, 1, 4)
        lookup = b"\xab\0\0\0" + struct.pack(">iiii", 0, 1, 7, 4)
        for code in (table, lookup):
            self.assertEqual(len(list(classfile.instructions(code))), 1)
            with self.assertRaises(classfile.ClassError):
                list(classfile.instructions(code[:-1]))
        with self.assertRaisesRegex(classfile.ClassError, "lookupswitch size"):
            list(classfile.instructions(b"\xab\0\0\0" + struct.pack(">ii", 0, -1)))
        with self.assertRaisesRegex(classfile.ClassError, "tableswitch size"):
            list(classfile.instructions(b"\xaa\0\0\0" + struct.pack(">iii", 0, 3, 1)))

    def test_descriptor_parsing_and_rejection(self):
        self.assertEqual(classfile.method_types("(J[Ljava/lang/Object;I)[J"), (["J", "[Ljava/lang/Object;", "I"], "[J"))
        self.assertEqual(classfile.method_types("()V"), ([], "V"))
        for descriptor in ("", "I", "(Lbroken)V", "(Q)V", "(I)", "()Vextra", "([)V"):
            with self.subTest(descriptor=descriptor), self.assertRaises(classfile.ClassError):
                classfile.method_types(descriptor)

    def test_branch_prefix_limits_and_zero_size_catalyst(self):
        ops = list(classfile.instructions(b"\x00\x00\xa7\xff\xfe\xb1"))
        self.assertEqual(extract.control_free_limit(ops, 6), 0)
        ops = list(classfile.instructions(b"\x00\xb1\x00"))
        self.assertEqual(extract.control_free_limit(ops, 3), 1)
        self.assertEqual(extract.symbolic_stack("gt:synthetic", 0), {"item": "gt:synthetic", "amount": 1, "unit": "item", "consumed": False})
        with self.assertRaises(extract.Unsupported):
            extract.symbolic_stack("gt:synthetic", 0, "mB")


if __name__ == "__main__":
    unittest.main()
