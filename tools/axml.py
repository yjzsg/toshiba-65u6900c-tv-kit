import struct, zipfile, sys

def decode_str(pool, idx):
    if idx == 0xFFFFFFFF or idx >= len(pool):
        return None
    return pool[idx]

def parse_pool(data, off):
    typ, hsize, size = struct.unpack_from('<HHI', data, off)
    count, styleCount, flags, stringsStart, stylesStart = struct.unpack_from('<IIIII', data, off+8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from('<%dI' % count, data, off+hsize)
    base = off + stringsStart
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            l = data[p]; p += 1
            if l & 0x80:
                l = ((l & 0x7f) << 8) | data[p]; p += 1
            b = data[p]; p += 1
            if b & 0x80:
                b = ((b & 0x7f) << 8) | data[p]; p += 1
            out.append(data[p:p+b].decode('utf-8', 'replace'))
        else:
            l = struct.unpack_from('<H', data, p)[0]; p += 2
            if l & 0x8000:
                l = ((l & 0x7fff) << 16) | struct.unpack_from('<H', data, p)[0]; p += 2
            out.append(data[p:p+l*2].decode('utf-16-le', 'replace'))
    return out, size

def parse_manifest(data):
    assert struct.unpack_from('<I', data, 0)[0] == 0x00080003
    pool = []
    elems = []   # (depth, name, attrs dict)
    depth = 0
    off = 8
    total = struct.unpack_from('<I', data, 4)[0]
    while off < total:
        typ, hsize, size = struct.unpack_from('<HHI', data, off)
        if typ == 0x0001:
            pool, _ = parse_pool(data, off)
        elif typ == 0x0102:
            line, comment = struct.unpack_from('<II', data, off+8)
            ns, name = struct.unpack_from('<II', data, off+16)
            astart, asize, acount, ididx, clidx, styidx = struct.unpack_from('<HHHHHH', data, off+24)
            attrs = {}
            p = off + 16 + astart
            for _ in range(acount):
                ans, aname, araw = struct.unpack_from('<III', data, p)
                tsize, res0, dtype, ddata = struct.unpack_from('<HBBI', data, p+12)
                an = decode_str(pool, aname)
                val = None
                if araw != 0xFFFFFFFF:
                    val = decode_str(pool, araw)
                else:
                    if dtype == 0x12:
                        val = 'true' if ddata != 0 else 'false'
                    elif dtype in (0x10, 0x11):
                        val = ddata
                    elif dtype == 0x03:
                        val = decode_str(pool, ddata)
                attrs[an] = val
                p += asize
            elems.append((depth, decode_str(pool, name), attrs))
            depth += 1
        elif typ == 0x0103:
            depth -= 1
        elif typ == 0x0000:
            break
        off += size
    return elems

if __name__ == '__main__':
    apk = sys.argv[1]
    z = zipfile.ZipFile(apk)
    data = z.read('AndroidManifest.xml')
    elems = parse_manifest(data)
    print('元素总数:', len(elems))
    print()
    print('=== 组件与导出状态 ===')
    for d, name, attrs in elems:
        if name in ('activity', 'service', 'receiver', 'provider', 'activity-alias'):
            cn = attrs.get('name')
            ex = attrs.get('exported')
            perm = attrs.get('permission')
            proc = attrs.get('process')
            print(f'[{name}] {cn}')
            print(f'    exported={ex}  permission={perm}  process={proc}')
    print()
    print('=== 全部 intent-filter action ===')
    for d, name, attrs in elems:
        if name == 'action' and attrs.get('name'):
            print('  ', attrs['name'])
