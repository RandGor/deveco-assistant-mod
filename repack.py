from pathlib import Path
import sys
import zipfile

original_path, apktool_path, new_dex_path, output_path = map(Path, sys.argv[1:])
replacement_names = {"AndroidManifest.xml", "res/layout/fragment_home.xml"}
added_name = "classes3.dex"

def is_old_signature(name: str) -> bool:
    upper = name.upper()
    if upper == "STAMP-CERT-SHA256":
        return True
    if not upper.startswith("META-INF/"):
        return False
    leaf = upper.rsplit("/", 1)[-1]
    return leaf == "MANIFEST.MF" or leaf.endswith((".SF", ".RSA", ".DSA", ".EC"))

with zipfile.ZipFile(original_path) as original, \
        zipfile.ZipFile(apktool_path) as built, \
        zipfile.ZipFile(output_path, "w") as output:
    original_names = set(original.namelist())
    if not replacement_names.issubset(original_names) or not replacement_names.issubset(built.namelist()):
        raise SystemExit("Patched manifest or home layout entry is missing")
    if added_name in original_names:
        raise SystemExit("Original APK already contains classes3.dex")
    for entry in original.infolist():
        if is_old_signature(entry.filename):
            continue
        data = built.read(entry.filename) if entry.filename in replacement_names else original.read(entry.filename)
        output.writestr(entry, data)
    output.writestr(added_name, new_dex_path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)

with zipfile.ZipFile(original_path) as original, zipfile.ZipFile(output_path) as output:
    names = set(output.namelist())
    if output.testzip() is not None:
        raise SystemExit("Repacked APK failed CRC validation")
    if len(names) != len(output.namelist()):
        raise SystemExit("Repacked APK has duplicate entries")
    for name in names - replacement_names - {added_name}:
        if output.read(name) != original.read(name):
            raise SystemExit("Unexpectedly changed ZIP entry: " + name)
print("Original APK entries preserved except manifest, home layout, new classes3.dex and old signatures")
