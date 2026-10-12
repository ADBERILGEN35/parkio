#!/usr/bin/env bash
# U14 run B drill host on Azure (Asana 1219001907916752): one disposable VM, provisioned, isolated, removed and verified gone.
# Owner approval 2026-10-12: Standard_B2s in West Europe, in its own resource group, for at most 4 hours, a total cost ceiling of
# USD 5, deleted the same day. No existing resource is changed (the backup storage account is only read, by the drill host).
#
# Run in Azure Cloud Shell (Bash), signed in as the subscription owner:
#   bash u14-drill-azure.sh quote             live list prices: the 4-hour total and a forgotten day against the ceiling
#   bash u14-drill-azure.sh rehearse          R0, cost 0: the reaper alone, with no VM. It creates a group holding a free marker
#                                             resource and a reaper due in 10 minutes, waits until Azure deleted the group,
#                                             then verifies that nothing tagged remains
#   bash u14-drill-azure.sh up [HOURS]        D1: the group, the reaper at the deadline (HOURS, at most 4), the budget alert, the
#                                             NSG (SSH from this terminal's address only), the VM; prints the SSH command
#   bash u14-drill-azure.sh egress open|storage|deny    the NSG's outbound phase (see below)
#   bash u14-drill-azure.sh ssh-from [IP]     move the SSH rule to this terminal's current address (Cloud Shell can change it)
#   bash u14-drill-azure.sh status            the group, the VM, the reaper, the outbound rules, the time left
#   bash u14-drill-azure.sh down              delete the group now, wait, verify
#   bash u14-drill-azure.sh verify-gone [GROUP]
#
# Cleanup never depends on the SSH session, the drill, this terminal or the VM:
#   1. Reaper: a Logic App (Consumption) in the drill group. Its system-assigned identity holds Contributor on that group
#      only. Its Recurrence trigger starts at the deadline and sends DELETE for the whole group to Azure Resource Manager,
#      again every 15 minutes until the group is gone. Deleting the group removes the reaper and its role assignment too.
#      `up` arms and checks the reaper BEFORE it creates the VM, and deletes the group if the reaper cannot be armed.
#   2. down: the operator deletes the group earlier, from any terminal with az; verify-gone then checks that the group and
#      every resource tagged parkio-drill=<id> are gone, subscription-wide.
#   3. Budget alert (best effort): a budget on the drill group e-mails the subscription's Owner role once actual cost passes
#      20% of 5 (in the billing currency). It exists only while the group exists, so it reports a group that outlived both.
# Outbound phases: open (Azure's default internet egress, for the bootstrap before any backup byte arrives); storage (HTTPS to
# the Storage.WestEurope service tag only, for the pull of the encrypted stamp); deny (no outbound at all, the platform
# resolver included, before anything is decrypted). Inbound: SSH from one address only.
# State: $HOME/.parkio-drill/<id>.env (no secret) and the SSH key pair <id>.key(.pub); every drill resource carries the tag
# parkio-drill=<id>, so `status`, `down` and `verify-gone` also find the group without the state file.
set -uo pipefail
CMD="${1:-}"; [ "$#" -gt 0 ] && shift
AZ="${U14D_AZ:-az}"
STATE_DIR="${U14D_STATE:-$HOME/.parkio-drill}"
LOCATION=westeurope
SIZE=Standard_B2s
IMAGE="Canonical:ubuntu-24_04-lts:server:latest"
DISK_SKU=Premium_LRS
DISK_GB=32
MAX_HOURS=4
CEILING=5
TAG=parkio-drill
ADMIN=drill
VM=parkio-rd
NSG=nsg-rd
REAPER=reaper
REHEARSAL_MINUTES="${U14D_REHEARSAL_MINUTES:-10}"
REAPER_PID=""
POLL="${U14D_POLL:-60}"
ARM=https://management.azure.com
stop() { echo "STOP: $*"; exit 1; }
az_() { "$AZ" "$@"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

need_login() {
  local acct
  acct="$(az_ account show --query '[id, name]' -o tsv 2>/dev/null | tr '\n' ' ')" || true
  [ -n "${acct// /}" ] || stop "not signed in to Azure (az login, or open Azure Cloud Shell)"
  SUB="$(printf '%s' "$acct" | cut -d' ' -f1)"
  echo "  subscription ${SUB:0:8}... ($(printf '%s' "$acct" | cut -d' ' -f2-))"
}
providers() {  # register the resource providers the drill uses, if needed (a subscription setting; no resource changes)
  local p st
  for p in "$@"; do
    st="$(az_ provider show -n "$p" --query registrationState -o tsv 2>/dev/null)"
    if [ "$st" != Registered ]; then
      echo "  registering resource provider $p (was ${st:-unknown}; free, no existing resource changes)"
      az_ provider register -n "$p" --wait -o none || stop "cannot register $p"
    fi
  done
}
find_group() {  # [GROUP] -> GROUP, ID
  if [ -n "${1:-}" ]; then GROUP="$1"; ID="$(az_ group show -n "$GROUP" --query "tags.\"$TAG\"" -o tsv 2>/dev/null)"; return; fi
  local rows n
  rows="$(az_ group list --tag "$TAG" --query "[].[name, tags.\"$TAG\"]" -o tsv 2>/dev/null)"
  n="$(printf '%s\n' "$rows" | grep -c . || true)"
  [ "$n" = 1 ] || stop "$n drill group(s) tagged $TAG found (want exactly 1); name one: $0 $CMD <group>"
  GROUP="$(printf '%s' "$rows" | cut -f1)"; ID="$(printf '%s' "$rows" | cut -f2)"
}
gone() { [ "$(az_ group exists -n "$1" 2>/dev/null)" = false ]; }

reaper() {  # GROUP ID DEADLINE: arm the reaper and check it; returns non-zero when it is not armed
  local group="$1" id="$2" deadline="$3" scope body pid i n st start
  scope="/subscriptions/$SUB/resourceGroups/$group"
  body="$(python3 -I - "$deadline" "$scope" "$LOCATION" "$TAG" "$id" <<'PY'
import json, sys
deadline, scope, location, tag, drill = sys.argv[1:]
print(json.dumps({
    "location": location, "tags": {tag: drill}, "identity": {"type": "SystemAssigned"},
    "properties": {"state": "Enabled", "definition": {
        "$schema": "https://schema.management.azure.com/providers/Microsoft.Logic/schemas/2016-06-01/workflowdefinition.json#",
        "contentVersion": "1.0.0.0",
        "triggers": {"deadline": {"type": "Recurrence",
                                  "recurrence": {"frequency": "Minute", "interval": 15, "startTime": deadline}}},
        "actions": {"delete_group": {"type": "Http", "runAfter": {}, "inputs": {
            "method": "DELETE", "uri": f"https://management.azure.com{scope}?api-version=2021-04-01",
            "authentication": {"type": "ManagedServiceIdentity", "audience": "https://management.azure.com/"}}}},
        "outputs": {}}}}))
PY
)"
  az_ rest --method put --url "$ARM$scope/providers/Microsoft.Logic/workflows/$REAPER?api-version=2019-05-01" \
    --body "$body" -o none || { echo "  the reaper could not be created"; return 1; }
  pid="$(az_ rest --method get --url "$ARM$scope/providers/Microsoft.Logic/workflows/$REAPER?api-version=2019-05-01" \
    --query identity.principalId -o tsv 2>/dev/null)"
  [ -n "$pid" ] || { echo "  the reaper has no identity"; return 1; }
  REAPER_PID="$pid"
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do   # a new identity can take a minute to replicate
    az_ role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal --role Contributor \
      --scope "$scope" -o none 2>/dev/null && break
    [ "$i" = 12 ] && { echo "  the reaper's identity could not get Contributor on the group"; return 1; }
    sleep "${U14D_RETRY_SLEEP:-10}"
  done
  st="$(az_ rest --method get --url "$ARM$scope/providers/Microsoft.Logic/workflows/$REAPER?api-version=2019-05-01" \
    --query properties.state -o tsv 2>/dev/null)"
  start="$(az_ rest --method get --url "$ARM$scope/providers/Microsoft.Logic/workflows/$REAPER?api-version=2019-05-01" \
    --query properties.definition.triggers.deadline.recurrence.startTime -o tsv 2>/dev/null)"
  n="$(az_ role assignment list --assignee "$pid" --scope "$scope" --query "length([?roleDefinitionName=='Contributor'])" -o tsv 2>/dev/null)"
  if [ "$st" = Enabled ] && [ "$start" = "$deadline" ] && [ "$n" = 1 ]; then
    echo "  reaper armed: Enabled, deletes the group at $deadline (then every 15 min until gone); identity holds Contributor on the group only"
    return 0
  fi
  echo "  the reaper is not armed (state ${st:-?}, start ${start:-?}, role assignments ${n:-?})"
  return 1
}

verify_gone() {  # GROUP ID
  local group="$1" id="$2" left
  if ! gone "$group"; then echo "  group $group: STILL EXISTS"; return 1; fi
  left="$(az_ resource list --tag "$TAG=$id" --query "[].id" -o tsv 2>/dev/null | grep -c . || true)"
  if [ "$left" != 0 ]; then echo "  $left resource(s) tagged $TAG=$id remain"; return 1; fi
  echo "  group $group: gone; resources tagged $TAG=$id anywhere in the subscription: 0"
  return 0
}

price_items() {  # filter -> JSON of the retail price API
  if [ -n "${U14D_PRICES_FIXTURE:-}" ]; then python3 -I - "$U14D_PRICES_FIXTURE" "$1" <<'PY'
import json, sys
fixtures = json.load(open(sys.argv[1]))
print(json.dumps(fixtures[sys.argv[2]]))
PY
  else
    curl -fsS --get "https://prices.azure.com/api/retail/prices" --data-urlencode "\$filter=$1" --data-urlencode currencyCode=USD
  fi
}

cmd_quote() {
  echo "U14 drill cost quote | $(now) | Azure retail list prices (USD), region $LOCATION"
  local vm disk ip logic
  vm="$(price_items "serviceName eq 'Virtual Machines' and armRegionName eq '$LOCATION' and armSkuName eq '$SIZE' and priceType eq 'Consumption'")" || stop "price API unreachable"
  disk="$(price_items "serviceName eq 'Storage' and armRegionName eq '$LOCATION' and skuName eq 'P4 LRS' and productName eq 'Premium SSD Managed Disks'")" || stop "price API unreachable"
  ip="$(price_items "serviceName eq 'Virtual Network' and armRegionName eq '$LOCATION' and productName eq 'IP Addresses'")" || stop "price API unreachable"
  logic="$(price_items "serviceName eq 'Logic Apps' and armRegionName eq '$LOCATION' and priceType eq 'Consumption'")" || stop "price API unreachable"
  python3 -I - "$vm" "$disk" "$ip" "$logic" "$MAX_HOURS" "$CEILING" <<'PY'
import json, sys
vm, disk, ip, logic = (json.loads(a)["Items"] for a in sys.argv[1:5])
hours, ceiling = int(sys.argv[5]), float(sys.argv[6])
def one(items, cond, what):
    m = [i for i in items if cond(i)]
    if not m:
        print(f"STOP: no list price for {what}"); sys.exit(1)
    return m[0]
v = one(vm, lambda i: i["productName"] == "Virtual Machines BS Series" and i["skuName"] == "B2s" and i["unitOfMeasure"] == "1 Hour", "B2s Linux")
d = one(disk, lambda i: i["meterName"] == "P4 LRS Disk" and i["unitOfMeasure"] == "1/Month", "P4 disk")
p = one(ip, lambda i: i["meterName"] == "Standard IPv4 Static Public IP" and i["unitOfMeasure"] == "1 Hour", "Standard static IPv4")
b = [i for i in logic if i["meterName"] == "Consumption Built-in Actions"]
free = min((i.get("tierMinimumUnits", 0) for i in b if i["retailPrice"] > 0), default=0)
per_hour = v["retailPrice"] + p["retailPrice"] + d["retailPrice"] / 730
rows = [("VM Standard_B2s, Linux, pay-as-you-go", f"{v['retailPrice']:.4f} / hour", v["effectiveStartDate"][:10]),
        ("OS disk Premium SSD P4 (32 GiB), no transaction charge", f"{d['retailPrice']:.4f} / month = {d['retailPrice'] / 730:.5f} / hour", d["effectiveStartDate"][:10]),
        ("Public IPv4, Standard, static", f"{p['retailPrice']:.4f} / hour", p["effectiveStartDate"][:10]),
        ("Reaper: Logic Apps Consumption built-in actions", f"0 for the first {int(free)} per month; the drill uses fewer than 20", "-"),
        ("NSG, virtual network, NIC, budget", "0", "-"),
        ("Inbound transfer; outbound up to 100 GB per month; same-region read of the stamp", "0", "-")]
for r in rows:
    print(f"  {r[0]:84} {r[1]:42} since {r[2]}")
total, day = per_hour * hours, per_hour * 24
print(f"  {hours} hours (the approved lifetime): {total:.2f} USD; ceiling {ceiling:.2f} USD")
print(f"  worst case, a whole day with the VM running (reaper and down both failed): {day:.2f} USD")
print("  storage read operations on the backup account (a few dozen GET/LIST) cost well under 0.01 USD")
ok = total <= ceiling and day <= ceiling
print("QUOTE " + ("WITHIN" if ok else "OVER") + f" THE CEILING ({total:.2f} USD for {hours} h)")
sys.exit(0 if ok else 1)
PY
}

cmd_rehearse() {
  echo "U14 R0 reaper rehearsal | $(now) | no VM, cost 0"
  need_login
  providers Microsoft.Logic
  local id group deadline t0 waited
  id="r0-$(date -u +%Y%m%d-%H%M%S)"; group="rg-parkio-drill-$id"
  deadline="$(date -u -d "+$REHEARSAL_MINUTES minutes" +%Y-%m-%dT%H:%M:%SZ)"
  az_ group create -n "$group" -l "$LOCATION" --tags "$TAG=$id" purpose=u14-reaper-rehearsal deadline="$deadline" -o none \
    || stop "cannot create $group"
  echo "  group $group created (tag $TAG=$id)"
  az_ network nsg create -g "$group" -n nsg-marker -l "$LOCATION" --tags "$TAG=$id" -o none || echo "  (no marker NSG)"
  if ! reaper "$group" "$id" "$deadline"; then
    az_ group delete -n "$group" --yes --no-wait -o none
    stop "the reaper could not be armed; the rehearsal group is being deleted by hand (check: $0 verify-gone $group)"
  fi
  echo "  waiting for the reaper; nothing to do here. If this terminal closes, check later: $0 verify-gone $group"
  t0="$(date +%s)"; waited=0
  while ! gone "$group"; do
    waited=$(( $(date +%s) - t0 ))
    [ "$waited" -gt $(( (REHEARSAL_MINUTES + 30) * 60 )) ] && stop "the group still exists 30 minutes after the deadline: the reaper failed; delete it: $0 down"
    echo "  $(now) group present (deadline $deadline)"
    sleep "$POLL"
  done
  local gone_at caller i
  gone_at="$(now)"
  echo "  $gone_at group gone"
  [[ "$gone_at" < "$deadline" ]] && stop "the group disappeared before the deadline $deadline: not the reaper; find out who deleted it"
  verify_gone "$group" "$id" || stop "something tagged $TAG=$id remains"
  for i in 1 2 3 4 5 6 7 8 9 10; do   # the activity log lags a few minutes
    caller="$(az_ monitor activity-log list --resource-group "$group" --offset 2h \
      --query "[?operationName.value=='Microsoft.Resources/subscriptions/resourceGroups/delete'].caller | [0]" -o tsv 2>/dev/null)"
    [ -n "$caller" ] && break
    sleep "${U14D_LOG_POLL:-30}"
  done
  if [ "$caller" = "$REAPER_PID" ]; then
    echo "  activity log: the group was deleted by the reaper's identity ($REAPER_PID)"
  elif [ -z "$caller" ]; then
    echo "  activity log: the deleting identity is not visible yet (the group vanished after the deadline)"
  else
    stop "the activity log names $caller, not the reaper's identity $REAPER_PID, as the deleting caller"
  fi
  echo "R0 REAPER REHEARSAL PASS - the reaper deleted its group at the deadline, and nothing tagged remains"
}

cmd_up() {
  local hours="${1:-$MAX_HOURS}" id group deadline myip key quota nw0 out pip ver
  echo "U14 D1 drill host up | $(now)"
  case "$hours" in [1-9]) ;; *) stop "HOURS must be 1 to $MAX_HOURS" ;; esac
  [ "$hours" -le "$MAX_HOURS" ] || stop "at most $MAX_HOURS hours were approved"
  need_login
  [ -z "$(az_ group list --tag "$TAG" --query '[].name' -o tsv 2>/dev/null)" ] \
    || stop "a drill group already exists ($(az_ group list --tag "$TAG" --query '[].name' -o tsv | tr '\n' ' ')): run down or verify-gone first"
  providers Microsoft.Compute Microsoft.Network Microsoft.Logic
  quota="$(az_ vm list-usage -l "$LOCATION" --query "[?name.value=='standardBSFamily' || name.value=='cores'].[name.value, currentValue, limit]" -o tsv 2>/dev/null)"
  printf '%s\n' "$quota" | awk -F'\t' 'NF == 3 && $2 + 2 > $3 { bad = 1 } END { exit bad }' \
    || stop "not enough vCPU quota in $LOCATION for 2 more cores: $(printf '%s' "$quota" | tr '\t\n' ' /')"
  myip="${U14D_SSH_FROM:-$(curl -fsS https://api.ipify.org 2>/dev/null)}"
  printf '%s' "$myip" | grep -q -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || stop "cannot tell this terminal's public IPv4 address (set U14D_SSH_FROM)"
  id="rd-$(date -u +%Y%m%d-%H%M)"; group="rg-parkio-drill-$id"
  deadline="$(date -u -d "+$hours hours" +%Y-%m-%dT%H:%M:%SZ)"
  nw0="$(az_ network watcher list --query "length([?location=='$LOCATION'])" -o tsv 2>/dev/null)"
  az_ group create -n "$group" -l "$LOCATION" --tags "$TAG=$id" purpose=u14-isolated-restore-drill deadline="$deadline" -o none \
    || stop "cannot create $group"
  echo "  group $group (tag $TAG=$id), deadline $deadline"
  if ! reaper "$group" "$id" "$deadline"; then
    az_ group delete -n "$group" --yes --no-wait -o none
    stop "the reaper could not be armed: no VM was created and the group is being deleted (check: $0 verify-gone $group)"
  fi
  if az_ rest --method put --url "$ARM/subscriptions/$SUB/resourceGroups/$group/providers/Microsoft.Consumption/budgets/rd-budget?api-version=2023-05-01" \
      --body "$(python3 -I - "$CEILING" <<'PY'
import datetime, json, sys
today = datetime.date.today()
start = today.replace(day=1)
end = (start + datetime.timedelta(days=62)).replace(day=1)
print(json.dumps({"properties": {"category": "Cost", "amount": float(sys.argv[1]), "timeGrain": "Monthly",
    "timePeriod": {"startDate": f"{start}T00:00:00Z", "endDate": f"{end}T00:00:00Z"},
    "notifications": {"actual_GreaterThan_20_Percent": {"enabled": True, "operator": "GreaterThan", "threshold": 20,
                                                       "thresholdType": "Actual", "contactRoles": ["Owner"]}}}}))
PY
)" -o none 2>/dev/null; then
    echo "  budget alert: 20% of $CEILING (billing currency), to the Owner role, on this group only"
  else
    echo "  budget alert: not available on this subscription (the reaper and down remain)"
  fi
  az_ network nsg create -g "$group" -n "$NSG" -l "$LOCATION" --tags "$TAG=$id" -o none || { az_ group delete -n "$group" --yes --no-wait -o none; stop "cannot create the NSG; the group is being deleted"; }
  az_ network nsg rule create -g "$group" --nsg-name "$NSG" -n rd-in-ssh --priority 100 --direction Inbound --access Allow \
    --protocol Tcp --source-address-prefixes "$myip/32" --source-port-ranges '*' --destination-address-prefixes '*' \
    --destination-port-ranges 22 -o none || { az_ group delete -n "$group" --yes --no-wait -o none; stop "cannot create the SSH rule; the group is being deleted"; }
  echo "  NSG $NSG: inbound SSH from $myip only; outbound phase open (bootstrap)"
  mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"
  key="$STATE_DIR/$id.key"
  ssh-keygen -q -t ed25519 -N '' -C "$id" -f "$key" || { az_ group delete -n "$group" --yes --no-wait -o none; stop "ssh-keygen failed; the group is being deleted"; }
  out="$(az_ vm create -g "$group" -n "$VM" --computer-name "parkio-rd-$(date -u +%Y%m%d)" -l "$LOCATION" --size "$SIZE" \
    --image "$IMAGE" --admin-username "$ADMIN" --authentication-type ssh --ssh-key-values "$key.pub" \
    --storage-sku "$DISK_SKU" --os-disk-size-gb "$DISK_GB" --os-disk-delete-option Delete --nic-delete-option Delete \
    --public-ip-sku Standard --nsg "$NSG" --tags "$TAG=$id" --query publicIpAddress -o tsv 2>/dev/null)"
  pip="$(printf '%s' "$out" | tr -d '[:space:]')"
  if [ -z "$pip" ]; then
    az_ group delete -n "$group" --yes --no-wait -o none
    stop "the VM could not be created; the group is being deleted (check: $0 verify-gone $group)"
  fi
  az_ resource list -g "$group" --query "[].id" -o tsv 2>/dev/null | while IFS= read -r rid; do
    [ -n "$rid" ] && az_ resource tag --is-incremental --tags "$TAG=$id" --ids "$rid" -o none 2>/dev/null
  done
  ver="$(az_ vm get-instance-view -g "$group" -n "$VM" --query storageProfile.imageReference.exactVersion -o tsv 2>/dev/null)"
  { echo "ID=$id"; echo "GROUP=$group"; echo "DEADLINE=$deadline"; echo "VM_IP=$pip"; echo "SSH_FROM=$myip"; echo "KEY=$key"
    echo "IMAGE_VERSION=${ver:-unknown}"; echo "NETWORK_WATCHERS_BEFORE=${nw0:-unknown}"; } > "$STATE_DIR/$id.env"
  echo "  VM $VM: $SIZE, Ubuntu 24.04 ${ver:-?}, $DISK_GB GiB $DISK_SKU, public IP $pip"
  echo "  SSH: ssh -i $key $ADMIN@$pip"
  echo "D1 UP - deadline $deadline: the reaper deletes everything then, whatever happens to this terminal or the drill"
}

nsg_rule() {  # GROUP NAME PRIORITY ACCESS DEST PORTS PROTOCOL
  az_ network nsg rule create -g "$1" --nsg-name "$NSG" -n "$2" --priority "$3" --direction Outbound --access "$4" \
    --protocol "$7" --source-address-prefixes '*' --source-port-ranges '*' --destination-address-prefixes "$5" \
    --destination-port-ranges "$6" -o none
}
nsg_drop() { az_ network nsg rule show -g "$1" --nsg-name "$NSG" -n "$2" -o none 2>/dev/null && az_ network nsg rule delete -g "$1" --nsg-name "$NSG" -n "$2" -o none; return 0; }
outbound() {
  az_ network nsg rule list -g "$1" --nsg-name "$NSG" --query "[?direction=='Outbound'] | sort_by(@, &priority)[].[priority, name, access, destinationAddressPrefix, destinationPortRange]" -o tsv 2>/dev/null \
    | awk -F'\t' '{ printf "    %s %s %s %s:%s\n", $1, $2, $3, $4, $5 }'
}
cmd_egress() {
  local phase="${1:-}"
  case "$phase" in open|storage|deny) ;; *) stop "usage: $0 egress open|storage|deny" ;; esac
  need_login; find_group "${2:-}"
  case "$phase" in
    open) nsg_drop "$GROUP" rd-out-storage; nsg_drop "$GROUP" rd-out-dns; nsg_drop "$GROUP" rd-out-deny ;;
    storage) nsg_drop "$GROUP" rd-out-dns
      nsg_rule "$GROUP" rd-out-storage 100 Allow Storage.WestEurope 443 Tcp || stop "cannot allow storage"
      nsg_rule "$GROUP" rd-out-deny 4000 Deny '*' '*' '*' || stop "cannot deny the rest" ;;
    deny) nsg_rule "$GROUP" rd-out-deny 4000 Deny '*' '*' '*' || stop "cannot deny outbound"
      nsg_rule "$GROUP" rd-out-dns 200 Deny AzurePlatformDNS '*' '*' || stop "cannot deny the platform resolver"
      nsg_drop "$GROUP" rd-out-storage ;;
  esac
  echo "  outbound rules of $NSG in $GROUP (custom, by priority):"
  outbound "$GROUP"
  echo "EGRESS $phase SET"
}

cmd_ssh_from() {
  local ip="${1:-${U14D_SSH_FROM:-$(curl -fsS https://api.ipify.org 2>/dev/null)}}"
  printf '%s' "$ip" | grep -q -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' || stop "cannot tell this terminal's public IPv4 address (pass it)"
  need_login; find_group "${2:-}"
  az_ network nsg rule create -g "$GROUP" --nsg-name "$NSG" -n rd-in-ssh --priority 100 --direction Inbound --access Allow \
    --protocol Tcp --source-address-prefixes "$ip/32" --source-port-ranges '*' --destination-address-prefixes '*' \
    --destination-port-ranges 22 -o none || stop "cannot update the SSH rule"
  echo "SSH FROM $ip ONLY"
}

cmd_status() {
  need_login; find_group "${1:-}"
  local scope="/subscriptions/$SUB/resourceGroups/$GROUP" dl
  dl="$(az_ group show -n "$GROUP" --query tags.deadline -o tsv 2>/dev/null)"
  echo "  group $GROUP (tag $TAG=$ID), deadline $dl, now $(now)"
  az_ resource list -g "$GROUP" --query "[].[type, name]" -o tsv 2>/dev/null | awk -F'\t' '{ printf "    %s %s\n", $1, $2 }'
  echo "  VM: $(az_ vm show -g "$GROUP" -n "$VM" -d --query '[powerState, publicIps]' -o tsv 2>/dev/null | tr '\n' ' ')"
  echo "  reaper: $(az_ rest --method get --url "$ARM$scope/providers/Microsoft.Logic/workflows/$REAPER?api-version=2019-05-01" --query '[properties.state, properties.definition.triggers.deadline.recurrence.startTime]' -o tsv 2>/dev/null | tr '\n' ' ')"
  echo "  outbound rules:"; outbound "$GROUP"
}

cmd_down() {
  need_login; find_group "${1:-}"
  echo "U14 drill down | $(now) | deleting $GROUP (tag $TAG=$ID)"
  az_ group delete -n "$GROUP" --yes --no-wait -o none || stop "the delete request failed"
  local t0; t0="$(date +%s)"
  while ! gone "$GROUP"; do
    [ $(( $(date +%s) - t0 )) -gt 1800 ] && stop "the group still exists after 30 minutes; run $0 verify-gone $GROUP later"
    echo "  $(now) deleting..."
    sleep "${U14D_DOWN_POLL:-20}"
  done
  verify_gone "$GROUP" "$ID" || stop "something tagged $TAG=$ID remains"
  [ -f "$STATE_DIR/$ID.env" ] && rm -f "$STATE_DIR/$ID.key" "$STATE_DIR/$ID.key.pub" && echo "  SSH key pair of $ID deleted"
  echo "DOWN VERIFIED - $GROUP and everything tagged $TAG=$ID are gone"
}

cmd_verify_gone() {
  need_login
  local group="${1:-}" id
  if [ -z "$group" ]; then find_group; group="$GROUP"; id="$ID"
  else id="$(printf '%s' "$group" | sed -n 's/^rg-parkio-drill-//p')"; fi
  [ -n "$id" ] || stop "cannot tell the drill ID of $group"
  verify_gone "$group" "$id" || stop "not gone yet"
  echo "VERIFIED GONE - $group"
}

case "$CMD" in
  quote) cmd_quote ;;
  rehearse) cmd_rehearse ;;
  up) cmd_up "$@" ;;
  egress) cmd_egress "$@" ;;
  ssh-from) cmd_ssh_from "$@" ;;
  status) cmd_status "$@" ;;
  down) cmd_down "$@" ;;
  verify-gone) cmd_verify_gone "$@" ;;
  *) echo "usage: $0 quote|rehearse|up [HOURS]|egress open|storage|deny|ssh-from [IP]|status|down|verify-gone [GROUP]"; exit 1 ;;
esac
