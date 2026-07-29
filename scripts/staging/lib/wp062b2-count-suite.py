import xml.etree.ElementTree as ET
from pathlib import Path
import json, sys
name=sys.argv[1]
d=Path(sys.argv[2])
out=Path(sys.argv[3])
tests=fail=err=skip=0
for f in d.glob("TEST-*.xml"):
  r=ET.parse(f).getroot()
  tests += int(r.attrib.get("tests",0))
  fail += int(r.attrib.get("failures",0))
  err += int(r.attrib.get("errors",0))
  skip += int(r.attrib.get("skipped",0))
o={"suite":name,"tests":tests,"failures":fail,"errors":err,"skipped":skip,"passed":tests-fail-err-skip}
out.write_text(json.dumps(o, indent=2)+"\n")
print(json.dumps(o))