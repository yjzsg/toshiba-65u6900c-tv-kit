# -*- coding: utf-8 -*-
"""Minimal DEX parser: list methods of classes matching a substring.

Usage: python dexcls.py <apk> <substring> [<substring> ...]
"""
import struct
import sys
import zipfile


def uleb(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


class Dex:
    def __init__(self, data):
        self.d = data
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         _fids_size, _fids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from(
            "<12I", data, 56)
        self._strs = {}

    def string(self, i):
        if i in self._strs:
            return self._strs[i]
        off = struct.unpack_from("<I", self.d, self.string_ids_off + 4 * i)[0]
        _, p = uleb(self.d, off)
        end = self.d.index(b"\x00", p)
        s = self.d[p:end].decode("utf-8", "replace")
        self._strs[i] = s
        return s

    def type(self, i):
        return self.string(struct.unpack_from(
            "<I", self.d, self.type_ids_off + 4 * i)[0])

    def proto(self, i):
        off = self.proto_ids_off + 12 * i
        shorty, ret, params_off = struct.unpack_from("<III", self.d, off)
        params = []
        if params_off:
            n = struct.unpack_from("<I", self.d, params_off)[0]
            for k in range(n):
                params.append(self.type(struct.unpack_from(
                    "<H", self.d, params_off + 4 + 2 * k)[0]))
        return self.type(ret), params

    def method(self, i):
        off = self.method_ids_off + 8 * i
        cls, proto, name = struct.unpack_from("<HHI", self.d, off)
        ret, params = self.proto(proto)
        return self.type(cls), self.string(name), ret, params

    def classes(self):
        for i in range(self.class_defs_size):
            off = self.class_defs_off + 32 * i
            (cls_idx, acc, sup, ifaces, src, anno,
             cdata_off, sv) = struct.unpack_from("<8I", self.d, off)
            yield self.type(cls_idx), cdata_off

    def methods_of(self, cdata_off):
        if not cdata_off:
            return
        p = cdata_off
        sf, p = uleb(self.d, p)
        inf, p = uleb(self.d, p)
        dm, p = uleb(self.d, p)
        vm, p = uleb(self.d, p)
        for _ in range(sf + inf):
            _, p = uleb(self.d, p)
            _, p = uleb(self.d, p)
        for count in (dm, vm):
            idx = 0
            for _ in range(count):
                diff, p = uleb(self.d, p)
                acc, p = uleb(self.d, p)
                code, p = uleb(self.d, p)
                idx += diff
                yield idx, acc


apk = sys.argv[1]
needles = sys.argv[2:]
z = zipfile.ZipFile(apk)
for dexname in [n for n in z.namelist() if n.endswith(".dex")]:
    dx = Dex(z.read(dexname))
    for cname, cdata in dx.classes():
        if not any(n.lower() in cname.lower() for n in needles):
            continue
        print("\n=== %s  [%s] ===" % (cname, dexname))
        seen = set()
        for midx, acc in dx.methods_of(cdata):
            cls, name, ret, params = dx.method(midx)
            sig = "%s(%s)" % (name, ", ".join(params))
            if sig in seen:
                continue
            seen.add(sig)
            flag = "public" if acc & 0x1 else "      "
            print("   %s %-40s -> %s" % (flag, sig, ret))
