import xml.etree.ElementTree as ET
from pathlib import Path
import json
suites={
 "parking-test": Path("services/parking-service/build/test-results/test"),
 "gateway-test": Path("services/gateway-service/build/test-results/test"),
 "auth-test": Path("services/auth-service/build/test-results/test"),
 "media-test": Path("services/media-service/build/test-results/test"),
 "parking-integrationTest": Path("services/parking-service/build/test-results/integrationTest"),
}
out={}
for name,d in suites.items():
  tests=fail=err=skip=0
  if not d.exists():
    out[name]={"missing":True}; continue
  for f in d.glob("TEST-*.xml"):
    root=ET.parse(f).getroot()
    tests += int(root.attrib.get("tests",0))
    fail += int(root.attrib.get("failures",0))
    err += int(root.attrib.get("errors",0))
    skip += int(root.attrib.get("skipped",0))
  out[name]={"tests":tests,"failures":fail,"errors":err,"skipped":skip,"passed":tests-fail-err-skip}
Path("build/operational-evidence/wp062b2-regression-20260729102728/test-counts.json").write_text(json.dumps(out, indent=2)+"\n")
print(json.dumps(out, indent=2))