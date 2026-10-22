"""Read the explicit runtime SBOM's project-cache JAR notices; never invent license text."""
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parent.parent
repository = Path(sys.argv[1] if len(sys.argv) > 1 else "/root/.m2/repository").resolve()
bom = json.loads((root / "docs/evidence/g4-backend-bom.json").read_text(encoding="utf-8-sig"))
blocks = ["Ticknova Java runtime third-party notices\nExtracted from installed JARs listed by the runtime SBOM.\nThis file does not license Ticknova original source.\n"]
missing, found = [], []
for component in bom.get("components", []):
    group, name, version = component.get("group", ""), component["name"], component["version"]
    if not group or not all(re.fullmatch(r"[A-Za-z0-9_.+-]+", value) for value in (group, name, version)):
        missing.append({"name": name, "reason": "No safe Maven runtime coordinates"})
        continue
    directory = repository / group.replace(".", "/") / name / version
    jars = sorted(directory.glob(f"{name}-{version}*.jar"))
    jars = [jar for jar in jars if not jar.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
    label = f"{group}:{name}:{version}"
    blocks.append(f"\n{'=' * 72}\n{label}\nSBOM declared licenses: {json.dumps(component.get('licenses', []), ensure_ascii=False)}\n")
    notice_count = 0
    for jar in jars:
        with zipfile.ZipFile(jar) as archive:
            for member in archive.infolist():
                if member.is_dir() or member.file_size > 2_000_000:
                    continue
                basename = member.filename.rsplit("/", 1)[-1]
                if not re.match(r"^(licen[sc]e|notice|copying)([._-]|$)", basename, flags=re.I):
                    continue
                # Read bytes in-place; never extract any archive entry to a path.
                text = archive.read(member).decode("utf-8", errors="replace")
                blocks.append(f"\n--- {jar.name}!/{member.filename} ---\n{text}\n")
                notice_count += 1
    if notice_count:
        found.append({"coordinate": label, "noticeEntries": notice_count})
    else:
        missing.append({"coordinate": label, "reason": "No bundled notice found; consult SBOM license declaration and upstream artifact"})
        blocks.append("No bundled notice found in this installed artifact; declaration above is not a substitute for upstream terms.\n")
(root / "backend/THIRD-PARTY-NOTICES.txt").write_text("".join(blocks), encoding="utf-8")
report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "source": "Actual runtime SBOM and project Maven cache", "components": len(bom.get("components", [])), "found": found, "missingBundledNotices": missing}
(root / "docs/evidence/g4-java-notices.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Java runtime notices: components={report['components']}, artifacts with notices={len(found)}, missing={len(missing)}")
