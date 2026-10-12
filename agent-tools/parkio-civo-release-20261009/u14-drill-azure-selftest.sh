#!/usr/bin/env bash
# Local self-test of u14-drill-azure.sh against a stateful fake `az` (groups, resources, tags, NSG rules, Logic App workflows
# with identities, role assignments, budgets, a reaper that deletes its group once "due") and the live retail-price responses
# of 2026-10-12 as fixtures. Nothing reaches Azure.
# A1 quote: 4 h = 0.24 USD, a forgotten day 1.46 USD -> WITHIN; A2 a VM price of 2 USD/h -> OVER, exit 1;
# A3 rehearse: Microsoft.Logic registered, group + marker, reaper body (Recurrence from the deadline every 15 min, DELETE of the
#    group with the managed identity), Contributor on the group only after two replication failures, the reaper deletes the
#    group, the activity log names the reaper's identity, verify-gone -> PASS; A3b the group gone before the deadline -> STOP;
#    A3c the activity log names a user -> STOP; A4 the role never granted -> the group is deleted, STOP;
# A5 up 5 / up 0 -> STOP; A6 an existing drill group -> STOP; A7 no quota -> STOP, nothing created;
# A8 up: reaper armed and checked before the VM, SSH from one address, budget, tags on every resource, state without
#    secrets, private key 600 and never printed; A9 the VM fails -> the group is deleted, STOP;
# A10 egress storage / deny / open -> the expected outbound rules; ssh-from moves the SSH rule to one new address; A11 down -> deleted, verified, key pair removed;
# A12 a leftover tagged resource -> verify-gone STOP; A13 not signed in -> STOP.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u14-drill-azure.sh"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/home"
cat > "$W/bin/az" <<'PY'
#!/usr/bin/env python3
import json, os, re, sys
S = os.environ["FAKE_STATE"]
st = json.load(open(S)) if os.path.exists(S) else {}
st.setdefault("groups", {}); st.setdefault("providers", {"Microsoft.Logic": "NotRegistered"}); st.setdefault("roles", [])
st.setdefault("log", []); st.setdefault("calls", {})
a = sys.argv[1:]
st["log"].append(" ".join(a))
def save(): json.dump(st, open(S, "w"))
def opt(name, default=None):
    return a[a.index(name) + 1] if name in a else default
def multi(name):
    if name not in a: return []
    out, i = [], a.index(name) + 1
    while i < len(a) and not a[i].startswith("-"): out.append(a[i]); i += 1
    return out
def out(s): sys.stdout.write(s + ("\n" if s else "")); save(); sys.exit(0)
def fail(msg="error", code=1): sys.stderr.write(msg + "\n"); save(); sys.exit(code)
def tags_of(pairs): return dict(p.split("=", 1) for p in pairs)
cmd = " ".join(x for x in a[:3] if not x.startswith("-"))
G = st["groups"]
if os.environ.get("FAKE_NOLOGIN") and a[:2] == ["account", "show"]: fail("Please run 'az login'")
if a[:2] == ["account", "show"]: out("00000000-sub-0000\nTest Subscription")
if a[:2] == ["provider", "show"]: out(st["providers"].get(opt("-n"), "Registered"))
if a[:2] == ["provider", "register"]: st["providers"][opt("-n")] = "Registered"; out("")
if a[:2] == ["group", "list"]:
    rows = [(n, g["tags"].get("parkio-drill")) for n, g in G.items() if "parkio-drill" in g["tags"] and not g.get("deleted")]
    q = opt("--query", "")
    out("\n".join(n if q == "[].name" else f"{n}\t{i}" for n, i in rows))
if a[:2] == ["group", "show"]:
    g = G.get(opt("-n"))
    if not g or g.get("deleted"): fail("not found")
    q = opt("--query")
    out(g["tags"].get("deadline", "") if "deadline" in q else g["tags"].get("parkio-drill", ""))
if a[:2] == ["group", "create"]:
    G[opt("-n")] = {"tags": tags_of(multi("--tags")), "res": {}, "deleting": 0}; out("")
if a[:2] == ["group", "exists"]:
    n = opt("-n"); g = G.get(n)
    if g and not g.get("deleted"):
        g["polls"] = g.get("polls", 0) + 1
        wf = g["res"].get("reaper")
        if g.get("deleting"):
            g["deleting"] -= 1
            if g["deleting"] <= 0: g["deleted"] = True
        elif wf and wf.get("armed") and g["polls"] >= int(os.environ.get("FAKE_REAP_AFTER", "2")):
            g["deleted"] = True; st["reaped"] = n
    out("true" if g and not g.get("deleted") else "false")
if a[:2] == ["group", "delete"]:
    g = G.get(opt("-n"))
    if g: g["deleting"] = 2
    st.setdefault("deletes", []).append(opt("-n")); out("")
if a[:3] == ["network", "nsg", "create"]:
    G[opt("-g")]["res"][opt("-n")] = {"type": "Microsoft.Network/networkSecurityGroups", "tags": tags_of(multi("--tags")), "rules": {}}; out("")
if a[:4] == ["network", "nsg", "rule", "create"]:
    nsg = G[opt("-g")]["res"][opt("--nsg-name")]
    nsg["rules"][opt("-n")] = {"priority": int(opt("--priority")), "direction": opt("--direction"), "access": opt("--access"),
        "dest": opt("--destination-address-prefixes"), "port": opt("--destination-port-ranges"), "src": opt("--source-address-prefixes")}
    out("")
if a[:4] == ["network", "nsg", "rule", "show"]:
    if opt("-n") in G[opt("-g")]["res"][opt("--nsg-name")]["rules"]: out("")
    fail("not found")
if a[:4] == ["network", "nsg", "rule", "delete"]:
    G[opt("-g")]["res"][opt("--nsg-name")]["rules"].pop(opt("-n"), None); out("")
if a[:4] == ["network", "nsg", "rule", "list"]:
    rules = sorted(((r["priority"], n, r) for n, r in G[opt("-g")]["res"][opt("--nsg-name")]["rules"].items() if r["direction"] == "Outbound"))
    out("\n".join(f"{p}\t{n}\t{r['access']}\t{r['dest']}\t{r['port']}" for p, n, r in rules))
if a[:3] == ["network", "watcher", "list"]: out("1")
if a[:2] == ["vm", "list-usage"]: out(os.environ.get("FAKE_USAGE", "standardBSFamily\t0\t10\ncores\t0\t10").replace("\\t", "\t").replace("\\n", "\n"))
if a[:1] == ["rest"]:
    url, method = opt("--url"), opt("--method")
    m = re.search(r"/resourceGroups/([^/]+)/providers/(Microsoft\.Logic/workflows|Microsoft\.Consumption/budgets)/([^?]+)", url)
    g = G[m.group(1)]
    if "budgets" in url:
        if os.environ.get("FAKE_NOBUDGET"): fail("budgets not supported")
        g["budget"] = json.loads(opt("--body")); out("")
    if method == "put":
        g["res"]["reaper"] = {"type": "Microsoft.Logic/workflows", "body": json.loads(opt("--body")), "pid": "pid-" + m.group(1),
                              "tags": json.loads(opt("--body")).get("tags", {})}
        st.setdefault("order", []).append("reaper"); out("")
    wf = g["res"].get("reaper"); q = opt("--query", "")
    if not wf: fail("not found")
    if q == "identity.principalId": out(wf["pid"])
    if q == "properties.state": out(wf["body"]["properties"]["state"])
    start = wf["body"]["properties"]["definition"]["triggers"]["deadline"]["recurrence"]["startTime"]
    if q.endswith("recurrence.startTime"): out(start)
    out(f"{wf['body']['properties']['state']}\n{start}")
if a[:3] == ["role", "assignment", "create"]:
    st["calls"]["role"] = st["calls"].get("role", 0) + 1
    if st["calls"]["role"] <= int(os.environ.get("FAKE_ROLE_FAILS", "2")): fail("PrincipalNotFound")
    st["roles"].append({"pid": opt("--assignee-object-id"), "role": opt("--role"), "scope": opt("--scope"),
                        "type": opt("--assignee-principal-type")})
    g = G[opt("--scope").rsplit("/", 1)[1]]
    if "reaper" in g["res"]: g["res"]["reaper"]["armed"] = True
    out("")
if a[:3] == ["role", "assignment", "list"]:
    out(str(sum(1 for r in st["roles"] if r["pid"] == opt("--assignee") and r["scope"] == opt("--scope") and r["role"] == "Contributor")))
if a[:2] == ["vm", "create"]:
    if os.environ.get("FAKE_VM_FAIL"): fail("SkuNotAvailable")
    g = G[opt("-g")]
    for n, t in (("parkio-rd", "Microsoft.Compute/virtualMachines"), ("parkio-rd_OsDisk", "Microsoft.Compute/disks"),
                 ("parkio-rdVMNic", "Microsoft.Network/networkInterfaces"), ("parkio-rdPublicIP", "Microsoft.Network/publicIPAddresses"),
                 ("parkio-rdVNET", "Microsoft.Network/virtualNetworks")):
        g["res"][n] = {"type": t, "tags": tags_of(multi("--tags")) if n == "parkio-rd" else {}}
    st["vmargs"] = a; st.setdefault("order", []).append("vm"); out("20.1.2.3")
if a[:2] == ["resource", "list"]:
    q = opt("--query")
    if opt("-g"):
        g = G[opt("-g")]
        if q == "[].id": out("\n".join(f"/subscriptions/x/resourceGroups/{opt('-g')}/providers/{r['type']}/{n}" for n, r in g["res"].items()))
        out("\n".join(f"{r['type']}\t{n}" for n, r in g["res"].items()))
    k, v = opt("--tag").split("=", 1)
    ids = [f"{gn}/{n}" for gn, g in G.items() if not g.get("deleted") for n, r in g["res"].items() if r["tags"].get(k) == v]
    ids += [x for x in os.environ.get("FAKE_LEFTOVER", "").split(",") if x]
    out("\n".join(ids))
if a[:2] == ["resource", "tag"]:
    rid = opt("--ids"); gn, n = rid.split("/resourceGroups/")[1].split("/")[0], rid.rsplit("/", 1)[1]
    k, v = opt("--tags").split("=", 1); G[gn]["res"][n]["tags"][k] = v; out("")
if a[:2] == ["vm", "get-instance-view"]: out("24.04.202610010")
if a[:3] == ["monitor", "activity-log", "list"]:
    g = G.get(opt("--resource-group"))
    out(os.environ.get("FAKE_CALLER") or (g["res"]["reaper"]["pid"] if g and g.get("deleted") and "reaper" in g["res"] else ""))
if a[:2] == ["vm", "show"]: out("VM running\n20.1.2.3")
fail(f"fake az: unhandled {a}")
PY
chmod +x "$W/bin/az"
FIX="$HERE/u14-drill-azure-prices-20261012.json"
[ -s "$FIX" ] || { echo "SELFTEST SETUP: missing price fixture $FIX"; exit 2; }
export U14D_AZ="$W/bin/az" FAKE_STATE="$W/state.json" U14D_STATE="$W/home/.parkio-drill" U14D_POLL=0 U14D_DOWN_POLL=0 U14D_LOG_POLL=0 \
  U14D_RETRY_SLEEP=0 U14D_SSH_FROM=198.51.100.7 U14D_PRICES_FIXTURE="$FIX" HOME="$W/home"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasE() { if grep -qE -- "$2" "$W/$1"; then ok "$1: /$2/"; else no "$1: /$2/"; fi; }
run() { local name="$1"; shift; bash "$TOOL" "$@" > "$W/$name" 2>&1; echo "exit=$?" >> "$W/$name"; }
fresh() { rm -f "$W/state.json"; rm -rf "$W/home/.parkio-drill"; }
q() { python3 -I -c "import json,sys; s=json.load(open('$W/state.json')); $1"; }

run a1.out quote; sed -e "s#$W#<W>#g" -e "s#$HERE#<tools>#g" "$W/a1.out"
has a1.out "4 hours (the approved lifetime): 0.24 USD; ceiling 5.00 USD"
has a1.out "worst case, a whole day with the VM running (reaper and down both failed): 1.46 USD"
has a1.out "QUOTE WITHIN THE CEILING (0.24 USD for 4 h)"; has a1.out "exit=0"
python3 -I - "$FIX" "$W/pricey.json" <<'PY'
import json, sys
f = json.load(open(sys.argv[1]))
for k, v in f.items():
    for i in v["Items"]:
        if i.get("skuName") == "B2s":
            i["retailPrice"] = 2.0
json.dump(f, open(sys.argv[2], "w"))
PY
U14D_PRICES_FIXTURE="$W/pricey.json" run a2.out quote; has a2.out "QUOTE OVER THE CEILING"; has a2.out "exit=1"

fresh; U14D_REHEARSAL_MINUTES=0 run a3.out rehearse; sed -e "s#$W#<W>#g" -e "s#$HERE#<tools>#g" "$W/a3.out"
has a3.out "registering resource provider Microsoft.Logic"
has a3.out "reaper armed: Enabled, deletes the group at"; has a3.out "R0 REAPER REHEARSAL PASS"; has a3.out "exit=0"
hasE a3.out "activity log: the group was deleted by the reaper's identity \\(pid-rg-parkio-drill-r0-"
q '
g = next(iter(s["groups"].values())); wf = g["res"]["reaper"]["body"]; d = wf["properties"]["definition"]
assert wf["identity"]["type"] == "SystemAssigned" and wf["properties"]["state"] == "Enabled"
r = d["triggers"]["deadline"]["recurrence"]; assert r["frequency"] == "Minute" and r["interval"] == 15 and r["startTime"] == g["tags"]["deadline"]
h = d["actions"]["delete_group"]["inputs"]; n = next(iter(s["groups"]))
assert h["method"] == "DELETE" and h["uri"] == f"https://management.azure.com/subscriptions/00000000-sub-0000/resourceGroups/{n}?api-version=2021-04-01"
assert h["authentication"] == {"type": "ManagedServiceIdentity", "audience": "https://management.azure.com/"}
assert s["roles"] == [{"pid": "pid-" + n, "role": "Contributor", "scope": f"/subscriptions/00000000-sub-0000/resourceGroups/{n}", "type": "ServicePrincipal"}]
assert s["reaped"] == n and "deletes" not in s and "nsg-marker" in g["res"]
' && ok "a3: reaper body, Contributor on the group only, deleted by the reaper (not by hand)" || no "a3 reaper"
fresh; run a3b.out rehearse; has a3b.out "STOP: the group disappeared before the deadline"; has a3b.out "exit=1"
fresh; U14D_REHEARSAL_MINUTES=0 FAKE_CALLER=owner@example.com run a3c.out rehearse
has a3c.out "STOP: the activity log names owner@example.com, not the reaper's identity"; has a3c.out "exit=1"
fresh; FAKE_ROLE_FAILS=99 run a4.out rehearse
has a4.out "the reaper's identity could not get Contributor on the group"; has a4.out "STOP: the reaper could not be armed"; has a4.out "exit=1"
q 'assert len(s["deletes"]) == 1' && ok "a4: the group was deleted by hand" || no "a4 delete"

fresh; run a5.out up 5; has a5.out "STOP: at most 4 hours were approved"
run a5b.out up 0; has a5b.out "STOP: HOURS must be 1 to 4"
fresh; "$U14D_AZ" group create -n rg-parkio-drill-rd-old -l westeurope --tags parkio-drill=rd-old >/dev/null
run a6.out up; has a6.out "STOP: a drill group already exists (rg-parkio-drill-rd-old"
fresh; FAKE_USAGE='standardBSFamily\t9\t10\ncores\t9\t10' run a7.out up
has a7.out "STOP: not enough vCPU quota in westeurope"; q 'assert s["groups"] == {}' && ok "a7: nothing created" || no "a7 created"

fresh; run a8.out up 3; sed -e "s#$W#<W>#g" -e "s#$HERE#<tools>#g" "$W/a8.out"
has a8.out "reaper armed: Enabled"; has a8.out "budget alert: 20% of 5"; has a8.out "NSG nsg-rd: inbound SSH from 198.51.100.7 only"
hasE a8.out "SSH: ssh -i .*/rd-[0-9]{8}-[0-9]{4}\.key drill@20\.1\.2\.3"; has a8.out "D1 UP - deadline"; has a8.out "exit=0"
q '
assert s["order"] == ["reaper", "vm"]
g = next(iter(s["groups"].values())); i = g["tags"]["parkio-drill"]
assert all(r["tags"].get("parkio-drill") == i for r in g["res"].values()), "untagged resource"
ssh = g["res"]["nsg-rd"]["rules"]["rd-in-ssh"]; assert ssh["src"] == "198.51.100.7/32" and ssh["port"] == "22" and ssh["direction"] == "Inbound"
v = s["vmargs"]; o = lambda k: v[v.index(k) + 1]
assert o("--size") == "Standard_B2s" and o("-l") == "westeurope" and o("--storage-sku") == "Premium_LRS" and o("--os-disk-size-gb") == "32"
assert o("--image") == "Canonical:ubuntu-24_04-lts:server:latest" and o("--authentication-type") == "ssh" and o("--nsg") == "nsg-rd"
b = g["budget"]["properties"]; assert b["amount"] == 5.0 and b["notifications"]["actual_GreaterThan_20_Percent"]["contactRoles"] == ["Owner"]
' && ok "a8: reaper before the VM, every resource tagged, SSH rule, VM spec, budget" || no "a8 order/spec"
S8="$(ls "$W"/home/.parkio-drill/*.env)"; K8="${S8%.env}.key"
[ "$(stat -c %a "$K8")" = 600 ] && ! grep -q -F -- "$(sed -n 2p "$K8")" "$W/a8.out" && ok "a8: private key 600, not printed" || no "a8 key"
grep -q -E '^(ID|GROUP|DEADLINE|VM_IP|SSH_FROM|KEY|IMAGE_VERSION|NETWORK_WATCHERS_BEFORE)=' "$S8" && [ "$(grep -c -v -E '^(ID|GROUP|DEADLINE|VM_IP|SSH_FROM|KEY|IMAGE_VERSION|NETWORK_WATCHERS_BEFORE)=' "$S8")" = 0 ] \
  && ok "a8: state file holds names only" || no "a8 state file"

run a10s.out egress storage; sed -n '/outbound rules/,$p' "$W/a10s.out"
hasE a10s.out "    100 rd-out-storage Allow Storage.WestEurope:443"; hasE a10s.out "    4000 rd-out-deny Deny \*:\*"
run a10d.out egress deny
hasE a10d.out "    200 rd-out-dns Deny AzurePlatformDNS:\*"; hasE a10d.out "    4000 rd-out-deny Deny \*:\*"
grep -q rd-out-storage "$W/a10d.out" && no "a10: storage rule left in deny" || ok "a10: no storage rule in deny"
run a10o.out egress open; [ "$(sed -n '/outbound rules/,/EGRESS/p' "$W/a10o.out" | grep -c 'rd-out')" = 0 ] && ok "a10: open has no custom outbound rule" || no "a10 open"
run a10f.out ssh-from 203.0.113.9; has a10f.out "SSH FROM 203.0.113.9 ONLY"
q 'g = next(x for x in s["groups"].values() if not x.get("deleted")); r = g["res"]["nsg-rd"]["rules"]["rd-in-ssh"]; assert r["src"] == "203.0.113.9/32" and r["direction"] == "Inbound" and r["port"] == "22"' \
  && ok "a10f: the SSH rule moved to one new address" || no "a10f ssh rule"
run a11.out down; has a11.out "DOWN VERIFIED"; has a11.out "SSH key pair of"; has a11.out "exit=0"
[ ! -e "$K8" ] && ok "a11: key pair removed" || no "a11 key left"
FAKE_LEFTOVER="/subscriptions/x/resourceGroups/NetworkWatcherRG/providers/x/leftover" run a12.out verify-gone "$(sed -n 's/^GROUP=//p' "$S8")"
has a12.out "1 resource(s) tagged parkio-drill="; has a12.out "STOP: not gone yet"; has a12.out "exit=1"
fresh; FAKE_VM_FAIL=1 run a9.out up
has a9.out "STOP: the VM could not be created; the group is being deleted"; q 'assert len(s["deletes"]) == 1' && ok "a9: group deleted" || no "a9"
FAKE_NOLOGIN=1 run a13.out up; has a13.out "STOP: not signed in to Azure"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
