"""Run with the parser's locked runtime interpreter, not the developer environment."""
from importlib.metadata import distributions
import json
import re

components = []
for dist in distributions():
    name = re.sub(r"[-_.]+", "-", dist.metadata["Name"]).lower()
    component = {"type": "library", "name": name, "version": dist.version,
                 "purl": f"pkg:pypi/{name}@{dist.version}"}
    license_name = dist.metadata.get("License-Expression") or dist.metadata.get("License")
    if license_name:
        component["licenses"] = [{"license": {"name": license_name}}]
    components.append(component)
print(json.dumps({"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
                  "components": sorted(components, key=lambda value: value["name"])},
                 ensure_ascii=False, indent=2))
