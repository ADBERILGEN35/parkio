from pathlib import Path
import re
root=Path("services/parking-service/src/main/resources/db/migration")
vers=[]
for p in root.glob("V*.sql"):
  m=re.match(r"V(\d+)__", p.name)
  if m: vers.append(int(m.group(1)))
vers=sorted(vers)
assert vers==list(range(min(vers), max(vers)+1)), vers
assert min(vers)==1
assert max(vers)>=26, vers
print("migrations_ok", vers[0], vers[-1], "count", len(vers))