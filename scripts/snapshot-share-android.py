#!/usr/bin/env python3
"""Freeze an Android-only hotfix without rebuilding or relabeling Windows."""
from pathlib import Path
import hashlib, json, re
root = Path(__file__).resolve().parents[1]
gradle = (root / "nearby-probe/build.gradle.kts").read_text()
version = re.search(r'versionName = "([0-9.]+)-preview"', gradle).group(1)
build = int(re.search(r'versionCode = (\d+)', gradle).group(1))
apk_dir = root / "nearby-probe/build/outputs/apk/debug"
metadata = json.loads((apk_dir / "output-metadata.json").read_text())
assert metadata["applicationId"] == "com.floatclip.probe"
element = metadata["elements"][0]
assert element["versionCode"] == build and element["versionName"] == version + "-preview"
data = (apk_dir / element["outputFile"]).read_bytes()
folder = root / "analysis/share-releases" / version
folder.mkdir(parents=True, exist_ok=True)
name = f"FloatClip-Share-{version}-preview.apk"
target = folder / name
if target.exists():
    if target.read_bytes() != data:
        raise SystemExit("Immutable Android release conflict; increase version/build")
else:
    with target.open("xb") as output:
        output.write(data)
manifest = {"version": version, "build": build, "name": name,
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
(folder / "android-checksums.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(json.dumps(manifest))
