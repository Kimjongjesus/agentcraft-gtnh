"""Read-only, bounded GT pack extraction; no JVM, imports from jars, or Java execution.

Static recipes describe explicit builder registrations, NOT the runtime registry.
Only self-contained builder chains in the control-flow-free prefix of run() are
accepted. Everything requiring unmodelled calls, locals, branches or metadata is
skipped. Symbolic identities deliberately do not pretend to be registry IDs.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import secrets
import stat
import sys
import zipfile
import zlib

from .classfile import ClassError, ClassFile, Reader, instructions, method_types
from .core import MAX_STORE_BYTES, ValidationError, validate_store

MAX_FILE = 512 * 1024 * 1024
MAX_MEMBER = 8 * 1024 * 1024
MAX_TOTAL = 64 * 1024 * 1024
MAX_DUMP = 32 * 1024 * 1024
MAX_RECORDS = 100000
GT = 'gregtech/api/enums/GTValues'
TIER = 'gregtech/api/enums/TierEU'
BUILDER = 'gregtech/api/util/GTRecipeBuilder'
MATERIAL = 'gregtech/api/enums/Materials'
PREFIX = 'gregtech/api/enums/OrePrefixes'
ITEMLIST = 'gregtech/api/enums/ItemList'
MAPS = 'gregtech/api/recipe/RecipeMaps'
ADDER = 'gregtech/api/interfaces/internal/IGTRecipeAdder'
ITEM = 'Lnet/minecraft/item/ItemStack;'
FLUID = 'Lnet/minecraftforge/fluids/FluidStack;'
BTYPE = 'L' + BUILDER + ';'


class ExtractionError(ValueError):
    """Unsafe input, exceeded bound, or invalid normalized data."""


class Unsupported(ValueError):
    """A static expression is not in the small supported language."""


def require(condition, message):
    if not condition:
        raise Unsupported(message)


def integer(value, lower=0, upper=2**63 - 1):
    return type(value) is int and lower <= value <= upper


def clean_path(path, *, exists=True):
    """Reject symlinks in every component, rather than resolving through them."""
    path = Path(os.path.abspath(os.fspath(path)))
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current /= part
        try:
            mode = current.lstat().st_mode
        except FileNotFoundError:
            if exists:
                raise ExtractionError('input path does not exist') from None
            continue
        if stat.S_ISLNK(mode):
            raise ExtractionError('symlink paths are not permitted')
    return path


def within(path, root):
    return path == root or root in path.parents


def open_no_symlinks(path, flags=os.O_RDONLY):
    """Open by directory descriptors so a symlink swap cannot redirect a read."""
    path = clean_path(path)
    if path == Path(path.anchor):
        return os.open(path.anchor, flags | os.O_NOFOLLOW | os.O_DIRECTORY)
    directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in path.parts[1:-1]:
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        return os.open(path.name, flags | os.O_NOFOLLOW, dir_fd=directory)
    finally:
        os.close(directory)


def read_regular(path, limit):
    fd = open_no_symlinks(path)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > limit:
            raise ExtractionError('input is not a bounded regular file')
        data = stream.read(limit + 1)
        if len(data) > limit:
            raise ExtractionError('input exceeds size bound')
        after = os.fstat(stream.fileno())
        if (info.st_size, info.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ExtractionError('input changed while reading')
        return data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate JSON object key')
        result[key] = value
    return result


def provenance(source, locator):
    return {'source': source, 'locator': locator}


def constant(cf, instruction):
    _, op, raw = instruction
    if 0x02 <= op <= 0x08:
        return op - 0x03
    if op in (0x09, 0x0a):
        return op - 0x09
    if op in (0x10, 0x11):
        return int.from_bytes(raw, 'big', signed=True)
    if op in (0x12, 0x13, 0x14):
        return cf.literal(int.from_bytes(raw, 'big'))
    raise Unsupported('nonliteral operand')


def member_ref(cf, instruction):
    return cf.ref(int.from_bytes(instruction[2][:2], 'big'))


def literal_arrays(cf):
    """Read literal array construction expressions, not arbitrary clinit code."""
    result = {}
    for method in cf.methods:
        if method.name != '<clinit>' or method.exceptions:
            continue
        ops = list(instructions(method.code))
        for i, ins in enumerate(ops):
            if ins[1] not in (0xbc, 0xbd) or i == 0:
                continue
            try:
                size = constant(cf, ops[i - 1])
                require(integer(size, 1, 256), 'array size')
                if ins[1] == 0xbc:
                    require(ins[2] == b'\x0b', 'only long literal arrays')
                    store, descriptor = 0x50, '[J'
                else:
                    require(cf.class_name(int.from_bytes(ins[2], 'big')) == 'java/lang/String', 'only String arrays')
                    store, descriptor = 0x53, '[Ljava/lang/String;'
                values, offsets = [None] * size, [None] * size
                j = i + 1
                for _ in range(size):
                    require(ops[j][1] == 0x59 and ops[j + 3][1] == store, 'array initializer shape')
                    index, value = constant(cf, ops[j + 1]), constant(cf, ops[j + 2])
                    require(integer(index, 0, size - 1) and offsets[index] is None, 'array slot')
                    require(type(value) is (int if store == 0x50 else str), 'array value type')
                    values[index], offsets[index] = value, ops[j + 2][0]
                    j += 4
                require(ops[j][1] == 0xb3, 'array assignment')
                owner, name, desc = member_ref(cf, ops[j])
                require(owner == cf.name and desc == descriptor, 'array field')
                require(name not in result, 'repeated array assignment')
                result[name] = (values, offsets, ops[j][0])
            except (Unsupported, ClassError, IndexError):
                continue
    return result


def bootstrap_target(cf, index):
    entry = cf.entry(index)
    require(entry[0] == 18, 'not invokedynamic')
    r = Reader(cf.class_attributes.get('BootstrapMethods', b''))
    entries = []
    for _ in range(r.u2()):
        handle = r.u2()
        args = [r.u2() for _ in range(r.u2())]
        entries.append((handle, args))
    handle, args = entries[entry[1]]
    bootstrap = cf.entry(handle)
    require(bootstrap[0] == 15 and bootstrap[1] == 6, 'bootstrap handle')
    owner, name, _ = cf.ref(bootstrap[2])
    require(owner == 'java/lang/invoke/LambdaMetafactory' and name == 'metafactory', 'unsupported lambda factory')
    require(len(args) == 3, 'lambda arguments')
    target = cf.entry(args[1])
    require(target[0] == 15 and target[1] == 6, 'lambda target is not static')
    return cf.ref(target[2])


def voltage_map(cf, arrays):
    """Recognize actual Arrays.stream(V).map(lambda).toArray() assignment.

    The only supported lambda is a straight BigInteger multiply/divide sequence;
    constants and operation order come from the class, not a tier formula table.
    """
    result = {}
    for method in cf.methods:
        if method.name != '<clinit>' or method.exceptions:
            continue
        ops = list(instructions(method.code))
        for i in range(len(ops) - 5):
            part = ops[i:i + 6]
            try:
                require([op[1] for op in part] == [0xb2, 0xb8, 0xba, 0xb9, 0xb9, 0xb3], 'stream shape')
                owner, array, desc = member_ref(cf, part[0])
                require(owner == cf.name and array in arrays and desc == '[J', 'stream input')
                require(member_ref(cf, part[1]) == ('java/util/Arrays', 'stream', '([J)Ljava/util/stream/LongStream;'), 'stream call')
                require(member_ref(cf, part[3]) == ('java/util/stream/LongStream', 'map', '(Ljava/util/function/LongUnaryOperator;)Ljava/util/stream/LongStream;'), 'stream map')
                require(member_ref(cf, part[4]) == ('java/util/stream/LongStream', 'toArray', '()[J'), 'stream result')
                target = bootstrap_target(cf, int.from_bytes(part[2][2][:2], 'big'))
                require(target[0] == cf.name and target[2] == '(J)J', 'lambda signature')
                lambdas = [m for m in cf.methods if (m.name, m.descriptor) == target[1:]]
                require(len(lambdas) == 1 and not lambdas[0].exceptions, 'lambda body')
                body = list(instructions(lambdas[0].code))
                values = []
                for value in arrays[array][0]:
                    stack = []
                    returned = False
                    for pc, op, raw in body:
                        if op == 0x1e:
                            stack.append(value)
                        elif op in (0x12, 0x13, 0x14, 0x09, 0x0a, 0x10, 0x11) or 0x02 <= op <= 0x08:
                            stack.append(constant(cf, (pc, op, raw)))
                        elif op in (0xb6, 0xb8):
                            ref = cf.ref(int.from_bytes(raw, 'big'))
                            if ref == ('java/math/BigInteger', 'valueOf', '(J)Ljava/math/BigInteger;'):
                                require(type(stack[-1]) is int, 'BigInteger argument')
                            elif ref == ('java/math/BigInteger', 'multiply', '(Ljava/math/BigInteger;)Ljava/math/BigInteger;'):
                                b, a = stack.pop(), stack.pop()
                                stack.append(a * b)
                            elif ref == ('java/math/BigInteger', 'divide', '(Ljava/math/BigInteger;)Ljava/math/BigInteger;'):
                                b, a = stack.pop(), stack.pop()
                                require(b != 0, 'division by zero')
                                stack.append((abs(a) // abs(b)) * (-1 if (a < 0) != (b < 0) else 1))
                            elif ref == ('java/math/BigInteger', 'longValueExact', '()J'):
                                require(integer(stack[-1], -2**63), 'long overflow')
                            else:
                                raise Unsupported('lambda call')
                        elif op == 0xad:
                            require(len(stack) == 1 and integer(stack[0]), 'lambda return')
                            values.append(stack[0])
                            returned = True
                            break
                        else:
                            raise Unsupported('lambda opcode')
                        require(len(stack) <= 64 and all(type(x) is int and x.bit_length() <= 256 for x in stack), 'lambda bound')
                    require(returned, 'lambda return absent')
                owner, name, desc = member_ref(cf, part[5])
                require(owner == cf.name and desc == '[J', 'stream assignment')
                result[name] = (values, part[5][0], target[1])
            except (Unsupported, ClassError, IndexError, ZeroDivisionError):
                continue
    return result


def symbolic_stack(item, amount, unit='item'):
    require(integer(amount, 0), 'stack amount')
    require(amount > 0 or unit == 'item', 'zero fluid stack')
    result = {'item': item, 'amount': max(1, amount), 'unit': unit}
    if amount == 0:
        result['consumed'] = False
    return result


class Array:
    def __init__(self, kind, size):
        require(integer(size, 0, 256), 'array size bound')
        self.kind = kind
        self.values = [None] * size


class Builder:
    def __init__(self):
        self.values = {}


class ChainVM:
    """Tiny expression interpreter. No jumps, locals, reflection, or host calls."""
    def __init__(self, cf, constants):
        self.cf = cf
        self.constants = constants
        self.dependencies = []
        self.stack = []
        self.result = None

    def invoke(self, owner, name, desc, args, receiver):
        if (owner, name, desc) == (ADDER, 'stdBuilder', '()' + BTYPE):
            require(receiver == ('field', GT, 'RA'), 'builder receiver')
            return Builder()
        if owner == 'gregtech/api/util/GTOreDictUnificator' and name == 'get':
            require(desc in ('(L' + PREFIX + ';Ljava/lang/Object;J)' + ITEM,
                             '(L' + PREFIX + ';L' + MATERIAL + ';J)' + ITEM), 'unificator overload')
            prefix, material, amount = args
            require(isinstance(prefix, tuple) and prefix[:2] == ('field', PREFIX), 'ore prefix')
            require(isinstance(material, tuple) and material[:2] == ('field', MATERIAL), 'material')
            return symbolic_stack('ore:' + prefix[2] + ':' + material[2], amount)
        if owner == ITEMLIST and name == 'get' and desc == '(J[Ljava/lang/Object;)' + ITEM:
            require(isinstance(receiver, tuple) and receiver[:2] == ('field', ITEMLIST), 'ItemList receiver')
            require(isinstance(args[1], Array) and args[1].values == [], 'ItemList fallback unsupported')
            return symbolic_stack('gt:ItemList:' + receiver[2], args[0])
        if owner == MATERIAL and name in ('getFluid', 'getGas', 'getMolten', 'getPlasma') and desc == '(J)' + FLUID:
            require(isinstance(receiver, tuple) and receiver[:2] == ('field', MATERIAL), 'fluid material')
            return symbolic_stack('gt:Materials:' + receiver[2] + ':' + name, args[0], 'mB')
        if (owner, name, desc) == ('gregtech/api/util/GTUtility', 'getIntegratedCircuit', '(I)' + ITEM):
            require(integer(args[0], 0, 24), 'circuit configuration')
            return symbolic_stack('gt:integrated_circuit:' + str(args[0]), 0)
        if owner != BUILDER or not isinstance(receiver, Builder):
            raise Unsupported('unsupported call ' + owner + '.' + name + desc)
        values = receiver.values
        if name in ('itemInputs', 'itemOutputs', 'fluidInputs', 'fluidOutputs'):
            expected = ITEM if name.startswith('item') else FLUID
            require(desc == '([' + expected + ')' + BTYPE, 'stack overload')
            require(name not in values, 'repeated stack setter')
            array = args[0]
            require(isinstance(array, Array) and array.kind == expected[1:-1], 'stack array type')
            require(all(isinstance(x, dict) and x.get('unit') == ('item' if name.startswith('item') else 'mB') for x in array.values), 'unresolved stack')
            if name.endswith('Outputs'):
                require(all(x.get('consumed', True) for x in array.values), 'zero output stack')
            values[name] = [dict(x) for x in array.values]
        elif name in ('duration', 'eut'):
            require(desc in ('(I)' + BTYPE, '(J)' + BTYPE), 'numeric setter signature')
            require(name not in values and integer(args[0]), 'numeric setter')
            values[name] = args[0]
        elif name == 'circuit' and desc == '(I)' + BTYPE:
            require('circuit' not in values and integer(args[0], 0, 24), 'circuit setter')
            values['circuit'] = symbolic_stack('gt:integrated_circuit:' + str(args[0]), 0)
        elif name == 'outputChances' and desc == '([I)' + BTYPE:
            require(name not in values and isinstance(args[0], Array) and args[0].kind == 'int', 'chance array')
            require(all(integer(x, 1, 10000) for x in args[0].values), 'unsupported chance')
            values[name] = args[0].values[:]
        elif name == 'addTo' and desc == '(Lgregtech/api/interfaces/IRecipeMap;)Ljava/util/Collection;':
            require(isinstance(args[0], tuple) and args[0][:2] == ('field', MAPS), 'recipe map')
            require('duration' in values and 'eut' in values, 'duration/eut missing')
            inputs = values.get('itemInputs', []) + values.get('fluidInputs', [])
            outputs = values.get('itemOutputs', []) + values.get('fluidOutputs', [])
            require(inputs and outputs, 'empty recipe')
            if 'circuit' in values:
                # An existing circuit can be replaced by applyPendingCircuit;
                # do not guess that replacement behaviour.
                require(not any(x['item'].startswith('gt:integrated_circuit:') or x['item'].startswith('gt:ItemList:Circuit_Integrated') for x in inputs), 'ambiguous circuit replacement')
                inputs = inputs + [values['circuit']]
            if 'outputChances' in values:
                require(len(values['outputChances']) == len(values.get('itemOutputs', [])), 'chance count')
                for output, chance in zip(outputs, values['outputChances']):
                    output['chance'] = chance / 10000
            self.result = {'machine': args[0][2], 'inputs': inputs, 'outputs': outputs,
                           'duration_ticks': values['duration'], 'eut': values['eut'],
                           'enabled': True, 'basis': 'static-bytecode'}
            return None
        else:
            raise Unsupported('unsupported builder method ' + name + desc)
        return receiver

    def run(self, ops):
        require(len(ops) <= 4096, 'chain instruction bound')
        for pc, op, raw in ops:
            stack = self.stack
            if 0x02 <= op <= 0x0a or op in (0x10, 0x11, 0x12, 0x13, 0x14):
                stack.append(constant(self.cf, (pc, op, raw)))
            elif op == 0xb2:
                ref = self.cf.ref(int.from_bytes(raw, 'big'))
                if ref in self.constants:
                    value, prov = self.constants[ref]
                    self.dependencies.extend(prov)
                    stack.append(value)
                else:
                    require(ref[0] in (GT, PREFIX, MATERIAL, ITEMLIST, MAPS), 'unsupported static field')
                    require(ref[0] != GT or ref[1] == 'RA', 'unknown GTValues field')
                    stack.append(('field', ref[0], ref[1]))
            elif op in (0xbc, 0xbd):
                size = stack.pop()
                if op == 0xbc:
                    require(raw == b'\x0a', 'only int primitive arrays')
                    kind = 'int'
                else:
                    kind = self.cf.class_name(int.from_bytes(raw, 'big'))
                    require(kind in (ITEM[1:-1], FLUID[1:-1], 'java/lang/Object'), 'array class')
                stack.append(Array(kind, size))
            elif op == 0x59:
                stack.append(stack[-1])
            elif op in (0x4f, 0x53):
                value, index, array = stack.pop(), stack.pop(), stack.pop()
                require(isinstance(array, Array) and integer(index, 0, len(array.values) - 1), 'array store')
                require((op == 0x4f) == (array.kind == 'int'), 'array store type')
                require(array.values[index] is None, 'repeated array store')
                array.values[index] = value
            elif op in (0x85,):
                require(integer(stack[-1], -2**31, 2**31 - 1), 'i2l operand')
            elif op in (0xb6, 0xb8, 0xb9):
                owner, name, desc = self.cf.ref(int.from_bytes(raw[:2], 'big'))
                arg_types, result_type = method_types(desc)
                args = [stack.pop() for _ in arg_types][::-1]
                receiver = None if op == 0xb8 else stack.pop()
                result = self.invoke(owner, name, desc, args, receiver)
                if result_type != 'V':
                    stack.append(result)
            else:
                raise Unsupported('unsupported opcode 0x%02x' % op)
            require(len(stack) <= 512, 'stack bound')
        require(self.result is not None and self.stack == [None], 'incomplete builder chain')
        return self.result


def control_free_limit(ops, length):
    """No branch, handler, early return, or backward target may enter a chain."""
    limit = length
    for pc, op, raw in ops:
        if 0x99 <= op <= 0xa8 or op in (0xc6, 0xc7, 0xc8, 0xc9):
            target = pc + int.from_bytes(raw, 'big', signed=True)
            limit = min(limit, pc, max(0, target))
        elif op in (0xaa, 0xab, 0xa9, 0xbf, 0xc4):
            # Switch target analysis is deliberately unsupported.
            return 0
        elif 0xac <= op <= 0xb1:
            limit = min(limit, pc)
    return limit


class Extractor:
    def __init__(self, root, version):
        self.root = clean_path(root)
        if not self.root.is_dir():
            raise ExtractionError('pack root must be a directory')
        if not isinstance(version, str) or not version.strip() or len(version) > 256:
            raise ExtractionError('pack version must be a nonempty short string')
        self.data = {'schema_version': 1, 'pack_version': version, 'sources': [],
                     'tiers': [], 'machines': [], 'recipes': [], 'facts': [],
                     'coverage': {'runtime_dump': False, 'runtime_registry_complete': False,
                                  'static_registration_coverage': 'partial',
                                  'warnings': [], 'static_skips': {},
                                  'limitations': [
                                      'No Java or pack classes are executed. Static registrations are not proof of the final runtime recipe set.',
                                      'Only self-contained simple builder chains in control-flow-free prefixes of base gregtech/loaders/postload/recipes run() methods are interpreted; helper methods, branches, loops, metadata, NBT and other loaders are not expanded.',
                                      'enabled=true on a static recipe means an unconditional registration expression, not runtime availability; loader invocation and recipe-map transformations are not verified.',
                                      'Symbolic IDs preserve OrePrefixes/Materials, ItemList fields and fluid getter identity, not resolved registry aliases. Circuit identities include configuration and are nonconsumed.',
                                      'Machine families and tiers are language-key metadata, not verified metatile registrations.',
                                      'Base jar members are read; multi-release variants are not selected as a runtime JVM would.'
                                  ]}}
        self.sources = {}
        self.constants = {}
        self.total_bytes = 0
        self.skips = Counter()

    def warn(self, message):
        if len(self.data['coverage']['warnings']) < 200:
            self.data['coverage']['warnings'].append(message)

    def source(self, path, payload):
        if path in self.sources:
            return self.sources[path]
        sid = 'source:' + hashlib.sha256(path.encode('utf-8')).hexdigest()
        self.sources[path] = sid
        self.data['sources'].append({'id': sid, 'path': path, 'sha256': hashlib.sha256(payload).hexdigest()})
        return sid

    def read_member(self, jar, name, jarpath):
        info = jar.getinfo(name)
        if info.file_size > MAX_MEMBER or info.file_size > max(1, info.compress_size) * 500:
            raise ExtractionError('jar member exceeds size or compression bound')
        self.total_bytes += info.file_size
        if self.total_bytes > MAX_TOTAL:
            raise ExtractionError('total selected jar bytes exceed bound')
        payload = jar.read(info)
        return payload, self.source(jarpath + '!/' + name, payload)

    def tiers(self, jar, jarpath):
        name = GT + '.class'
        if name not in jar.namelist():
            self.warn('GTValues class absent; no voltage tiers extracted')
            return
        payload, sid = self.read_member(jar, name, jarpath)
        cf = ClassFile(payload)
        arrays = literal_arrays(cf)
        mapped = voltage_map(cf, arrays)
        known = {key: (val[0], [provenance(sid, '<clinit>()V bytecode:' + str(val[2]) + ' field:' + key)]) for key, val in arrays.items()}
        for key, (values, pc, method) in mapped.items():
            known[key] = (values, [provenance(sid, '<clinit>()V bytecode:' + str(pc) + ' field:' + key), provenance(sid, method + '(J)J bytecode:0')])
        for key, (values, prov) in known.items():
            if key in ('V', 'VA', 'VP', 'VN'):
                self.constants[(GT, key, '[Ljava/lang/String;' if key == 'VN' else '[J')] = (values, prov)
        self.data['coverage']['voltage_arrays'] = sorted(key for key in known if key in ('V', 'VA', 'VN', 'VP'))
        self.data['coverage']['voltage_fields'] = {key: desc for key, desc in cf.fields.items() if key in ('V', 'VA', 'VN', 'VP')}
        if 'VA' not in known:
            self.warn('No supported VA initializer found; decoded VP is used when available')
        recipe_array = 'VP' if 'VP' in known else 'VA' if 'VA' in known else None
        self.data['coverage']['tier_recipe_array'] = recipe_array
        if not all(key in known for key in ('V', 'VN')) or recipe_array is None:
            self.warn('Incomplete voltage arrays; tier records skipped rather than guessed')
        else:
            names, voltages, recipes = known['VN'][0], known['V'][0], known[recipe_array][0]
            if len(names) != len(voltages) or len(names) != len(recipes) or len(set(names)) != len(names):
                raise ExtractionError('inconsistent voltage array lengths/names')
            for i, (tier, voltage, eut) in enumerate(zip(names, voltages, recipes)):
                if not isinstance(tier, str) or not integer(voltage) or not integer(eut):
                    raise ExtractionError('invalid voltage array value')
                self.data['tiers'].append({'id': tier, 'index': i, 'voltage': voltage, 'recipe_eut': eut,
                                           'provenance': known['VN'][1] + known['V'][1] + known[recipe_array][1]})
        name = TIER + '.class'
        if name not in jar.namelist():
            return
        payload, tier_sid = self.read_member(jar, name, jarpath)
        cf = ClassFile(payload)
        for method in cf.methods:
            if method.name != '<clinit>' or method.exceptions:
                continue
            ops = list(instructions(method.code))
            for i in range(len(ops) - 3):
                part = ops[i:i + 4]
                try:
                    require(part[0][1] == 0xb2 and part[2][1] == 0x2f and part[3][1] == 0xb3, 'tier expression')
                    array_ref = member_ref(cf, part[0])
                    require(array_ref in self.constants, 'tier array')
                    values, prov = self.constants[array_ref]
                    index = constant(cf, part[1])
                    require(integer(index, 0, len(values) - 1), 'tier index')
                    ref = member_ref(cf, part[3])
                    require(ref[0] == TIER and ref[2] == 'J', 'tier field')
                    self.constants[ref] = (values[index], prov + [provenance(tier_sid, '<clinit>()V bytecode:' + str(part[3][0]) + ' field:' + ref[1])])
                    if ref[1].startswith('RECIPE_'):
                        for tier in self.data['tiers']:
                            if tier['id'] == ref[1][len('RECIPE_'):]:
                                tier['recipe_eut'] = values[index]
                                tier['provenance'] = list({(p['source'], p['locator']): p
                                                          for p in tier['provenance'] + self.constants[ref][1]}.values())
                except (Unsupported, ClassError, IndexError):
                    continue

    def language(self, payload, sid):
        by_index = {t['index']: t['id'] for t in self.data['tiers']}
        seen = set()
        for line_no, line in enumerate(payload.decode('utf-8-sig').splitlines(), 1):
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            key = key.strip()
            if not key.startswith('gt.blockmachines.') or not key.endswith('.name') or not value.strip():
                continue
            if key in seen:
                raise ExtractionError('duplicate machine language key')
            seen.add(key)
            family = key[len('gt.blockmachines.'):-len('.name')]
            match = re.search(r'\.tier\.(\d+)$', family)
            tier = by_index.get(int(match[1])) if match else None
            if match:
                family = family[:match.start()]
            if family.startswith('basicmachine.'):
                family = family[len('basicmachine.'):]
            if not family:
                self.warn('Generic language label without machine family skipped: ' + key)
                continue
            self.data['machines'].append({'id': key, 'name': value.strip(), 'tier': tier,
                                          'family': family, 'basis': 'language-metadata',
                                          'provenance': [provenance(sid, 'line:' + str(line_no) + ' key:' + key)]})

    def config(self, path):
        payload = read_regular(path, MAX_MEMBER)
        sid = self.source(path.relative_to(self.root).as_posix(), payload)
        categories, listing = [], False
        for line_no, raw in enumerate(payload.decode('utf-8-sig').splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            if listing:
                if line == '>':
                    listing = False
                continue
            if line.endswith('<'):
                listing = True
                self.warn('Config list skipped at line:' + str(line_no))
                continue
            if line.endswith('{'):
                name = line[:-1].strip().strip('"')
                if not name or len(categories) >= 32:
                    raise ExtractionError('invalid config nesting')
                categories.append(name)
                continue
            if line == '}':
                if not categories:
                    raise ExtractionError('unbalanced config closing brace')
                categories.pop()
                continue
            match = re.fullmatch(r'([BIDS]):(.+?)=(.*)', line)
            if not match:
                self.warn('Unsupported config line:' + str(line_no))
                continue
            kind, key, text = match.groups()
            key, text = key.strip().strip('"'), text.strip()
            try:
                if kind == 'B':
                    if text.lower() not in ('true', 'false'):
                        raise ValueError()
                    value = text.lower() == 'true'
                elif kind == 'I':
                    value = int(text)
                    if not integer(value, -2**63):
                        raise ValueError()
                elif kind == 'D':
                    value = float(text)
                    if not math.isfinite(value):
                        raise ValueError()
                else:
                    value = text
            except ValueError:
                self.warn('Invalid config scalar at line:' + str(line_no))
                continue
            keypath = '.'.join(categories + [key])
            self.data['facts'].append({'id': 'config:' + keypath, 'category': 'machine-config', 'value': value,
                                       'provenance': [provenance(sid, 'line:' + str(line_no) + ' key:' + keypath)]})
        if categories or listing:
            raise ExtractionError('unterminated config structure')

    def recipes(self, payload, sid):
        cf = ClassFile(payload)
        for method in cf.methods:
            if (method.name, method.descriptor) != ('run', '()V'):
                continue
            ops = list(instructions(method.code))
            if method.exceptions:
                self.skips['methods with exception handlers'] += 1
                continue
            limit = control_free_limit(ops, len(method.code))
            if limit < len(method.code) - 1:
                self.skips['control-flow suffixes'] += 1
            i = 0
            while i + 1 < len(ops) and ops[i][0] < limit:
                if ops[i][1] != 0xb2 or member_ref(cf, ops[i]) != (GT, 'RA', 'L' + ADDER + ';'):
                    i += 1
                    continue
                start = i
                i += 1
                if ops[i][1] != 0xb9 or member_ref(cf, ops[i]) != (ADDER, 'stdBuilder', '()' + BTYPE):
                    continue
                self.data['coverage']['static_builder_candidates'] += 1
                end = i
                while end < len(ops) and ops[end][0] < limit and end - start <= 4096:
                    if ops[end][1] == 0xb6 and member_ref(cf, ops[end])[:2] == (BUILDER, 'addTo'):
                        break
                    if end > i and ops[end][1] == 0xb2 and member_ref(cf, ops[end])[:2] == (GT, 'RA'):
                        break
                    end += 1
                try:
                    require(end < len(ops) and ops[end][0] < limit and ops[end][1] == 0xb6 and member_ref(cf, ops[end])[:2] == (BUILDER, 'addTo'), 'incomplete/oversized chain')
                    require(end + 1 < len(ops) and ops[end + 1][1] == 0x57, 'registration result reused')
                    vm = ChainVM(cf, self.constants)
                    record = vm.run(ops[start:end + 1])
                    record['id'] = 'static:' + sid.split(':')[1][:16] + ':' + cf.name + ':run:' + str(ops[start][0])
                    prov = [provenance(sid, 'run()V bytecode:' + str(ops[start][0]) + '-' + str(ops[end][0]))] + vm.dependencies
                    record['provenance'] = list({(p['source'], p['locator']): p for p in prov}.values())
                    self.data['recipes'].append(record)
                except (Unsupported, IndexError, ClassError) as exc:
                    self.skips[str(exc) or 'invalid stack expression'] += 1
                i = max(i + 1, end)

    def dump(self, path):
        path = clean_path(path)
        if not within(path, self.root):
            raise ExtractionError('explicit dumps must be inside pack root for pack-relative provenance')
        payload = read_regular(path, MAX_DUMP)
        try:
            data = json.loads(payload, object_pairs_hook=unique_object,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError('nonfinite JSON')))
        except (ValueError, UnicodeError, RecursionError):
            raise ExtractionError('invalid normalized dump JSON') from None
        if not isinstance(data, dict) or type(data.get('schema_version')) is not int or data['schema_version'] != 1 or not isinstance(data.get('recipes'), list):
            raise ExtractionError('dump requires schema_version 1 and recipes array')
        if len(data['recipes']) > MAX_RECORDS:
            raise ExtractionError('dump recipe count exceeds bound')
        sid = self.source(path.relative_to(self.root).as_posix(), payload)
        for i, recipe in enumerate(data['recipes']):
            validate_recipe(recipe, dump=True)
            recipe = dict(recipe)
            recipe['provenance'] = [provenance(sid, '/recipes/' + str(i))]
            self.data['recipes'].append(recipe)
        self.data['coverage']['runtime_dump'] = True
        self.data['coverage']['runtime_dump_files'] += 1
        self.data['coverage']['runtime_dump_recipes'] += len(data['recipes'])

    def run(self, dumps):
        coverage = self.data['coverage']
        coverage.update(static_builder_candidates=0, static_loader_classes=0, runtime_dump_files=0, runtime_dump_recipes=0)
        coverage['static_builder_candidates_scope'] = 'Recognized sites in control-flow-free run() prefixes only; not a denominator for whole-pack coverage'
        coverage['symbolic_ids'] = {
            'ore:<prefix>:<material>': 'OrePrefixes and Materials static field names; not a canonical registry ID',
            'gt:ItemList:<field>': 'ItemList static field name; aliases are not resolved',
            'gt:Materials:<material>:<getter>': 'Materials fluid getter identity; getFluid/getGas/getMolten/getPlasma stay distinct',
            'gt:integrated_circuit:<configuration>': 'Zero-size integrated circuit, represented as one nonconsumed item'
        }
        mods = self.root / 'mods'
        jars = []
        if mods.exists() or mods.is_symlink():
            clean_path(mods)
            jars = sorted(mods.glob('gregtech-*.jar'))
        if len(jars) > 1:
            raise ExtractionError('multiple gregtech jars; use a root with one selected version')
        if not jars:
            self.warn('No gregtech jar found under mods/')
        for path in jars:
            path = clean_path(path)
            if not path.is_file() or path.stat().st_size > MAX_FILE:
                raise ExtractionError('jar must be a bounded regular file')
            jarpath = path.relative_to(self.root).as_posix()
            # ZipFile reads only directory and selected members; it extracts nothing.
            fd = open_no_symlinks(path)
            with os.fdopen(fd, 'rb') as stream, zipfile.ZipFile(stream) as jar:
                before = os.fstat(stream.fileno())
                if not stat.S_ISREG(before.st_mode) or before.st_size > MAX_FILE:
                    raise ExtractionError('jar must be a bounded regular file')
                infos = jar.infolist()
                if len(infos) > MAX_RECORDS:
                    raise ExtractionError('jar directory exceeds bound')
                names = set()
                for info in infos:
                    name = info.filename
                    parsed = PurePosixPath(name)
                    if (name in names or parsed.is_absolute() or '..' in parsed.parts or '\\' in name
                            or any(ord(c) < 32 for c in name) or stat.S_ISLNK(info.external_attr >> 16)):
                        raise ExtractionError('unsafe or duplicate jar member path')
                    names.add(name)
                try:
                    self.tiers(jar, jarpath)
                except (ClassError, UnicodeError, Unsupported) as exc:
                    self.warn('Voltage bytecode unsupported: ' + str(exc))
                language = 'assets/gregtech/lang/en_US.lang'
                if language in names:
                    self.language(*self.read_member(jar, language, jarpath))
                else:
                    self.warn('GregTech English language member absent')
                loaders = sorted(n for n in names if n.startswith('gregtech/loaders/postload/recipes/') and n.endswith('.class') and '$' not in n)
                if len(loaders) > 512:
                    raise ExtractionError('loader class count exceeds bound')
                for name in loaders:
                    payload, sid = self.read_member(jar, name, jarpath)
                    coverage['static_loader_classes'] += 1
                    try:
                        self.recipes(payload, sid)
                    except (ClassError, UnicodeError) as exc:
                        self.skips['unreadable loader class: ' + str(exc)] += 1
                after = os.fstat(stream.fileno())
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                    raise ExtractionError('jar changed while reading')
        config = self.root / 'config/GregTech/MachineStats.cfg'
        if config.exists() or config.is_symlink():
            self.config(clean_path(config))
        else:
            self.warn('config/GregTech/MachineStats.cfg absent')
        for dump in dumps:
            self.dump(dump)
        if not coverage['runtime_dump']:
            self.warn('No runtime dump supplied; recipes are partial static registrations only')
        coverage['static_skips'] = dict(sorted(self.skips.items()))
        coverage['static_recipes'] = sum(r['basis'] == 'static-bytecode' for r in self.data['recipes'])
        coverage['counts'] = {kind: len(self.data[kind]) for kind in ('sources', 'tiers', 'machines', 'recipes', 'facts')}
        for kind in ('sources', 'tiers', 'machines', 'recipes', 'facts'):
            rows = self.data[kind]
            if len(rows) > MAX_RECORDS or len({row['id'] for row in rows}) != len(rows):
                raise ExtractionError('duplicate IDs or count bound in ' + kind)
        for recipe in self.data['recipes']:
            validate_recipe(recipe)
        try:
            return validate_store(self.data)
        except ValidationError as exc:
            raise ExtractionError('extracted store violates schema: ' + str(exc)) from exc


def validate_recipe(recipe, *, dump=False):
    required = {'id', 'machine', 'inputs', 'outputs', 'duration_ticks', 'eut', 'enabled', 'basis'}
    if not isinstance(recipe, dict) or not required.issubset(recipe):
        raise ExtractionError('recipe missing required fields')
    if set(recipe) - required - {'provenance'}:
        raise ExtractionError('unsupported recipe fields would lose prerequisites')
    for field in ('id', 'machine'):
        if not isinstance(recipe[field], str) or not recipe[field].strip() or len(recipe[field]) > 2048:
            raise ExtractionError('invalid recipe string')
    if type(recipe['enabled']) is not bool or recipe['basis'] not in ('runtime-dump', 'static-bytecode'):
        raise ExtractionError('invalid recipe enabled/basis')
    if dump and recipe['basis'] != 'runtime-dump':
        raise ExtractionError('normalized dumps must explicitly use basis runtime-dump')
    for field in ('duration_ticks', 'eut'):
        if recipe[field] is not None and not integer(recipe[field]):
            raise ExtractionError('invalid recipe numeric scalar')
    for field in ('inputs', 'outputs'):
        stacks = recipe[field]
        if not isinstance(stacks, list) or not stacks or len(stacks) > 256:
            raise ExtractionError('invalid recipe stack array')
        for stack in stacks:
            if not isinstance(stack, dict) or set(stack) - {'item', 'amount', 'unit', 'consumed', 'chance'} or not {'item', 'amount', 'unit'}.issubset(stack):
                raise ExtractionError('invalid stack fields')
            if not isinstance(stack['item'], str) or not stack['item'].strip() or len(stack['item']) > 2048:
                raise ExtractionError('invalid stack identity')
            if not integer(stack['amount'], 1) or stack['unit'] not in ('item', 'mB'):
                raise ExtractionError('invalid stack amount/unit')
            if 'consumed' in stack and type(stack['consumed']) is not bool:
                raise ExtractionError('invalid consumed flag')
            chance = stack.get('chance', 1)
            if type(chance) not in (int, float) or not math.isfinite(chance) or not 0 < chance <= 1:
                raise ExtractionError('invalid stack chance')


def extract_pack(pack_root, pack_version, dumps=()):
    """Return a normalized store without writing any files."""
    try:
        return Extractor(pack_root, pack_version).run(dumps)
    except (zipfile.BadZipFile, UnicodeError, zlib.error, NotImplementedError, RuntimeError) as exc:
        raise ExtractionError('invalid archive or text encoding') from exc


extract = extract_pack


def write_store(data, output, pack_root):
    validate_store(data)
    root = clean_path(pack_root)
    output = clean_path(output, exists=False)
    if within(output, root):
        raise ExtractionError('output must be outside the input pack tree')
    if output.exists() and (not output.is_file() or output.stat().st_nlink != 1):
        raise ExtractionError('output must be absent or a non-hardlinked regular file')
    parent = clean_path(output.parent)
    encoded = (json.dumps(data, separators=(',', ':'), ensure_ascii=True, allow_nan=False) + '\n').encode('utf-8')
    if len(encoded) > MAX_STORE_BYTES:
        raise ExtractionError('output exceeds size bound')
    directory = open_no_symlinks(parent, os.O_RDONLY | os.O_DIRECTORY)
    temporary = '.pack-kb-' + secrets.token_hex(12) + '.json'
    created = False
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=directory)
        created = True
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        try:
            target = os.stat(output.name, dir_fd=directory, follow_symlinks=False)
        except FileNotFoundError:
            target = None
        if target is not None and (not stat.S_ISREG(target.st_mode) or target.st_nlink != 1):
            raise ExtractionError('unsafe output target')
        os.replace(temporary, output.name, src_dir_fd=directory, dst_dir_fd=directory)
        created = False
        os.fsync(directory)
    finally:
        if created:
            os.unlink(temporary, dir_fd=directory)
        os.close(directory)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack-root', required=True)
    parser.add_argument('--pack-version', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--dump', action='append', default=[], help='explicit normalized dump inside pack root')
    args = parser.parse_args(argv)
    try:
        root, output = clean_path(args.pack_root), clean_path(args.output, exists=False)
        if within(output, root):
            raise ExtractionError('output must be outside the input pack tree')
        data = extract_pack(root, args.pack_version, args.dump)
        write_store(data, output, root)
    except (ExtractionError, ValidationError, OSError, ClassError, RecursionError) as exc:
        # OSError filenames could disclose absolute host paths; keep CLI errors portable.
        message = str(exc) if isinstance(exc, (ExtractionError, ClassError)) else type(exc).__name__
        print('pack-kb extraction failed: ' + message, file=sys.stderr)
        return 2
    print(json.dumps({'counts': data['coverage']['counts'], 'runtime_dump': data['coverage']['runtime_dump'],
                      'static_registration_coverage': data['coverage']['static_registration_coverage']}, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
