# -*- coding: utf-8 -*-
"""Dump components + their intent-filter actions, grouped, from a binary AndroidManifest."""
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from axml import parse_manifest  # noqa: E402

apk = sys.argv[1]
data = zipfile.ZipFile(apk).read("AndroidManifest.xml")
elems = parse_manifest(data)

# walk with depth to attach actions to their owning component
comp = None
comps = []
depth_of_comp = None
for d, name, attrs in elems:
    if name in ("activity", "service", "receiver", "provider", "activity-alias"):
        comp = {"kind": name, "name": attrs.get("name"),
                "exported": attrs.get("exported"),
                "perm": attrs.get("permission"),
                "proc": attrs.get("process"),
                "actions": [], "cats": []}
        comps.append(comp)
        depth_of_comp = d
    elif name == "action" and comp is not None and attrs.get("name"):
        comp["actions"].append(attrs["name"])
    elif name == "category" and comp is not None and attrs.get("name"):
        comp["cats"].append(attrs["name"])

print("components: %d" % len(comps))
print()
for c in comps:
    if not c["actions"] and c["exported"] != "true":
        continue
    print("[%s] %s" % (c["kind"], c["name"]))
    print("    exported=%s perm=%s proc=%s" % (c["exported"], c["perm"], c["proc"]))
    for a in c["actions"]:
        print("    action: %s" % a)
    for k in c["cats"]:
        print("    cat:    %s" % k)
print()
print("=== permissions declared ===")
for d, name, attrs in elems:
    if name == "uses-permission" and attrs.get("name"):
        print("  ", attrs["name"])
