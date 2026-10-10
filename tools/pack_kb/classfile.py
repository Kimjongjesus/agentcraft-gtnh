"""Bounded, read-only JVM class-file decoder. This module never executes Java."""
from __future__ import annotations

import argparse
import struct
import zipfile
from dataclasses import dataclass

MAX_CLASS = 8 * 1024 * 1024


class ClassError(ValueError):
    """Malformed or unsupported class data."""


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def take(self, count):
        if count < 0 or self.pos + count > len(self.data):
            raise ClassError("truncated class file")
        result = self.data[self.pos:self.pos + count]
        self.pos += count
        return result

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.take(struct.calcsize('>' + fmt)))[0]

    def u1(self):
        return self.number('B')

    def u2(self):
        return self.number('H')

    def u4(self):
        return self.number('I')


@dataclass
class Method:
    name: str
    descriptor: str
    code: bytes
    exceptions: int


class ClassFile:
    def __init__(self, data):
        if len(data) > MAX_CLASS:
            raise ClassError("class exceeds size bound")
        r = Reader(data)
        if r.u4() != 0xCAFEBABE:
            raise ClassError("invalid class magic")
        r.u2()
        self.major = r.u2()
        count = r.u2()
        self.pool = [None] * count
        i = 1
        while i < count:
            tag = r.u1()
            if tag == 1:
                # Modified UTF-8 differs for NUL and surrogate code units.
                text = r.take(r.u2()).replace(b'\xc0\x80', b'\0')
                entry = (tag, text.decode('utf-8', errors='surrogatepass'))
            elif tag in (3, 4, 5, 6):
                entry = (tag, r.number({3: 'i', 4: 'f', 5: 'q', 6: 'd'}[tag]))
            elif tag in (7, 8, 16, 19, 20):
                entry = (tag, r.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                entry = (tag, r.u2(), r.u2())
            elif tag == 15:
                entry = (tag, r.u1(), r.u2())
            else:
                raise ClassError("unsupported constant-pool tag")
            self.pool[i] = entry
            i += 2 if tag in (5, 6) else 1
        r.u2()
        self.name = self.class_name(r.u2())
        r.u2()
        r.take(2 * r.u2())
        self.constants = {}
        self.fields = {}
        for _ in range(r.u2()):
            r.u2()
            name, descriptor = self.utf(r.u2()), self.utf(r.u2())
            self.fields[name] = descriptor
            for attr, payload in self.attributes(r):
                if attr == 'ConstantValue' and len(payload) == 2:
                    self.constants[name] = self.literal(int.from_bytes(payload, 'big'))
        self.methods = []
        for _ in range(r.u2()):
            r.u2()
            name, descriptor = self.utf(r.u2()), self.utf(r.u2())
            for attr, payload in self.attributes(r):
                if attr == 'Code':
                    c = Reader(payload)
                    c.u2()
                    c.u2()
                    length = c.u4()
                    if length > 65535:
                        raise ClassError("method exceeds JVM code bound")
                    code = c.take(length)
                    exceptions = c.u2()
                    c.take(exceptions * 8)
                    self.attributes(c)
                    if c.pos != len(payload):
                        raise ClassError("invalid Code attribute length")
                    self.methods.append(Method(name, descriptor, code, exceptions))
        self.class_attributes = dict(self.attributes(r))
        if r.pos != len(data):
            raise ClassError("trailing class data")

    def entry(self, index):
        if not 0 < index < len(self.pool) or self.pool[index] is None:
            raise ClassError("invalid constant-pool index")
        return self.pool[index]

    def utf(self, index):
        entry = self.entry(index)
        if entry[0] != 1:
            raise ClassError("expected UTF8 constant")
        return entry[1]

    def class_name(self, index):
        entry = self.entry(index)
        if entry[0] != 7:
            raise ClassError("expected class constant")
        return self.utf(entry[1])

    def literal(self, index):
        entry = self.entry(index)
        if entry[0] in (3, 4, 5, 6):
            return entry[1]
        if entry[0] == 8:
            return self.utf(entry[1])
        raise ClassError("not a scalar constant")

    def ref(self, index):
        entry = self.entry(index)
        if entry[0] not in (9, 10, 11):
            raise ClassError("expected member reference")
        nt = self.entry(entry[2])
        if nt[0] != 12:
            raise ClassError("expected name-and-type")
        return self.class_name(entry[1]), self.utf(nt[1]), self.utf(nt[2])

    def attributes(self, r):
        result = []
        for _ in range(r.u2()):
            name = self.utf(r.u2())
            result.append((name, r.take(r.u4())))
        return result


# Operand bytes for fixed-width JVM opcodes. Unknown/reserved opcodes fail closed.
WIDTH = {op: 0 for op in range(0x00, 0x10)}
WIDTH.update({0x10: 1, 0x11: 2, 0x12: 1, 0x13: 2, 0x14: 2})
WIDTH.update({op: 1 for op in range(0x15, 0x1a)})
WIDTH.update({op: 0 for op in range(0x1a, 0x36)})
WIDTH.update({op: 1 for op in range(0x36, 0x3b)})
WIDTH.update({op: 0 for op in range(0x3b, 0x84)})
WIDTH[0x84] = 2
WIDTH.update({op: 0 for op in range(0x85, 0x99)})
WIDTH.update({op: 2 for op in range(0x99, 0xaa)})
WIDTH[0xa9] = 1
WIDTH.update({op: 0 for op in range(0xac, 0xb2)})
WIDTH.update({op: 2 for op in range(0xb2, 0xb9)})
WIDTH.update({0xb9: 4, 0xba: 4, 0xbb: 2, 0xbc: 1, 0xbd: 2,
              0xbe: 0, 0xbf: 0, 0xc0: 2, 0xc1: 2, 0xc2: 0, 0xc3: 0,
              0xc5: 3, 0xc6: 2, 0xc7: 2, 0xc8: 4, 0xc9: 4})


def instructions(code):
    """Yield (offset, opcode, raw operand bytes), checking every boundary."""
    r = Reader(code)
    while r.pos < len(code):
        pc = r.pos
        op = r.u1()
        start = r.pos
        if op in (0xaa, 0xab):
            r.take((-r.pos) % 4)
            r.take(4)
            if op == 0xaa:
                low, high = r.number('i'), r.number('i')
                count = high - low + 1
                if count < 0 or count > 16384:
                    raise ClassError("invalid tableswitch size")
                r.take(4 * count)
            else:
                count = r.number('i')
                if count < 0 or count > 8192:
                    raise ClassError("invalid lookupswitch size")
                r.take(8 * count)
        elif op == 0xc4:
            wide = r.u1()
            if wide == 0x84:
                r.take(4)
            elif wide in tuple(range(0x15, 0x1a)) + tuple(range(0x36, 0x3b)) + (0xa9,):
                r.take(2)
            else:
                raise ClassError("invalid wide opcode")
        elif op in WIDTH:
            r.take(WIDTH[op])
        else:
            raise ClassError("unsupported opcode 0x%02x" % op)
        yield pc, op, code[start:r.pos]


def method_types(descriptor):
    """Return argument descriptors and return descriptor (not word counts)."""
    def one(pos):
        start = pos
        while pos < len(descriptor) and descriptor[pos] == '[':
            pos += 1
        if pos >= len(descriptor):
            raise ClassError("invalid descriptor")
        if descriptor[pos] == 'L':
            end = descriptor.find(';', pos)
            if end == -1:
                raise ClassError("invalid object descriptor")
            pos = end + 1
        elif descriptor[pos] in 'BCDFIJSZV':
            pos += 1
        else:
            raise ClassError("invalid descriptor type")
        return descriptor[start:pos], pos
    if not descriptor.startswith('('):
        raise ClassError("invalid method descriptor")
    args, pos = [], 1
    while pos < len(descriptor) and descriptor[pos] != ')':
        arg, pos = one(pos)
        args.append(arg)
    ret, pos = one(pos + 1)
    if pos != len(descriptor):
        raise ClassError("trailing descriptor data")
    return args, ret


def main():
    parser = argparse.ArgumentParser(description='Read-only class-file inspection')
    parser.add_argument('jar')
    parser.add_argument('member', nargs='?')
    parser.add_argument('--method', default='run')
    parser.add_argument('--limit', type=int, default=300)
    args = parser.parse_args()
    # Reuse the extractor's read guards; the inspection CLI is not an escape
    # hatch for symlink or archive bounds. The decoder above has no filesystem IO.
    from tools.pack_kb.extract import clean_path, open_no_symlinks, MAX_FILE
    import os
    import stat
    path = clean_path(args.jar)
    if path.stat().st_size > MAX_FILE:
        raise ClassError('jar exceeds size bound')
    fd = open_no_symlinks(path)
    with os.fdopen(fd, 'rb') as stream, zipfile.ZipFile(stream) as jar:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
            raise ClassError('jar is not a regular file')
        if len(jar.infolist()) > 100000:
            raise ClassError('jar directory exceeds bound')
        if not args.member:
            for name in jar.namelist():
                if 'GTValues' in name or 'TierEU' in name:
                    print(name)
            return
        info = jar.getinfo(args.member)
        if info.file_size > MAX_CLASS:
            raise ClassError('class exceeds size bound')
        cf = ClassFile(jar.read(info))
        print('CLASS', cf.name, 'CONSTANTS', cf.constants)
        for method in cf.methods:
            if method.name != args.method:
                continue
            print('METHOD', method.name, method.descriptor, 'exceptions', method.exceptions)
            for i, (pc, op, raw) in enumerate(instructions(method.code)):
                if i >= args.limit:
                    break
                detail = raw.hex()
                if 0xb2 <= op <= 0xb9:
                    detail = repr(cf.ref(int.from_bytes(raw[:2], 'big')))
                elif op in (0x12, 0x13, 0x14):
                    try:
                        detail = repr(cf.literal(int.from_bytes(raw, 'big')))
                    except ClassError:
                        pass
                print(pc, hex(op), detail)


if __name__ == '__main__':
    main()
