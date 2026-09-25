#!/usr/bin/env bash
# Scoped invite-production NSG deployment (PROD-DEPLOY-01B-03C2).
#
# Default action compiles the scoped Bicep template only. --what-if previews
# the NSG-only deployment. --apply requires --confirm PROD-DEPLOY-01B-03C3.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEMPLATE="$ROOT/infra/azure/invite-production/nsg-only.bicep"
RESOURCE_GROUP="rg-parkio-invite-production-we"
LOCATION="westeurope"
NSG_NAME="nsg-parkio-invite-app"
DEPLOYMENT_NAME="prod-deploy-01b-nsg-only"
APPLY_CONFIRMATION_TOKEN="PROD-DEPLOY-01B-03C3"
MODE="validate"
CONFIRMATION=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --validate) MODE="validate"; shift ;;
    --what-if) MODE="what-if"; shift ;;
    --apply) MODE="apply"; shift ;;
    --confirm) CONFIRMATION="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

for tool in az; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: required tool not found: $tool" >&2
    exit 2
  fi
done

AZ_PATH="$(command -v az)"
AZ_TEMPLATE="$TEMPLATE"
if [[ "$AZ_PATH" == /mnt/c/* ]] && command -v wslpath >/dev/null 2>&1; then
  AZ_TEMPLATE="$(wslpath -w "$TEMPLATE")"
fi

assert_target_lock() {
  if ! az account show --query id -o tsv >/dev/null 2>&1; then
    echo "ERROR: Azure CLI is not authenticated." >&2
    exit 2
  fi

  if [ "$(az group exists --name "$RESOURCE_GROUP" | tr -d '\r')" != "true" ]; then
    echo "ERROR: expected resource group $RESOURCE_GROUP does not exist." >&2
    exit 2
  fi

  local rg_location
  rg_location="$(az group show --name "$RESOURCE_GROUP" --query location -o tsv --only-show-errors | tr -d '\r')"
  if [ "$rg_location" != "$LOCATION" ]; then
    echo "ERROR: resource group location mismatch (expected $LOCATION, got $rg_location)." >&2
    exit 2
  fi

  if ! az network nsg show --resource-group "$RESOURCE_GROUP" --name "$NSG_NAME" --query id -o tsv >/dev/null 2>&1; then
    echo "ERROR: expected NSG $NSG_NAME not found in $RESOURCE_GROUP." >&2
    exit 2
  fi
}

assert_live_nsg_baseline() {
  local https_count http_count
  https_count="$(az network nsg rule list \
    --resource-group "$RESOURCE_GROUP" \
    --nsg-name "$NSG_NAME" \
    --query "length([?name=='Allow-Https-From-Internet'])" \
    -o tsv --only-show-errors | tr -d '\r')"
  http_count="$(az network nsg rule list \
    --resource-group "$RESOURCE_GROUP" \
    --nsg-name "$NSG_NAME" \
    --query "length([?name=='Allow-Http-From-Internet'])" \
    -o tsv --only-show-errors | tr -d '\r')"

  if [ "${https_count:-0}" != "1" ]; then
    echo "ERROR: live NSG must contain exactly one Allow-Https-From-Internet rule." >&2
    exit 2
  fi

  local https_proto https_port https_prio https_access https_source
  https_proto="$(az network nsg rule show \
    --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
    --name Allow-Https-From-Internet --query protocol -o tsv --only-show-errors | tr -d '\r')"
  https_port="$(az network nsg rule show \
    --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
    --name Allow-Https-From-Internet --query destinationPortRange -o tsv --only-show-errors | tr -d '\r')"
  https_prio="$(az network nsg rule show \
    --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
    --name Allow-Https-From-Internet --query priority -o tsv --only-show-errors | tr -d '\r')"
  https_access="$(az network nsg rule show \
    --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
    --name Allow-Https-From-Internet --query access -o tsv --only-show-errors | tr -d '\r')"
  https_source="$(az network nsg rule show \
    --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
    --name Allow-Https-From-Internet --query sourceAddressPrefix -o tsv --only-show-errors | tr -d '\r')"

  if [ "$https_proto" != "Tcp" ] || [ "$https_port" != "443" ] || [ "$https_prio" != "100" ] \
    || [ "$https_access" != "Allow" ] || [ "$https_source" != "Internet" ]; then
    echo "ERROR: live HTTPS rule semantics are unexpected." >&2
    exit 2
  fi

  if [ "${http_count:-0}" != "0" ]; then
    local http_port http_prio
    http_port="$(az network nsg rule show \
      --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
      --name Allow-Http-From-Internet --query destinationPortRange -o tsv --only-show-errors 2>/dev/null | tr -d '\r' || true)"
    http_prio="$(az network nsg rule show \
      --resource-group "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
      --name Allow-Http-From-Internet --query priority -o tsv --only-show-errors 2>/dev/null | tr -d '\r' || true)"
    if [ "$http_port" = "80" ] && [ "$http_prio" = "110" ]; then
      echo "INFO: live HTTP rule already present with expected semantics; scoped apply may be no-op."
      return 0
    fi
    echo "ERROR: conflicting Allow-Http-From-Internet rule exists with unexpected semantics." >&2
    exit 2
  fi
}

validate_scoped_what_if_output() {
  local outfile="$1"
  if [ ! -s "$outfile" ]; then
    echo "ERROR: scoped what-if produced no output." >&2
    exit 2
  fi

  if ! grep -q 'nsg-parkio-invite-app' "$outfile"; then
    echo "ERROR: scoped what-if missing target NSG." >&2
    exit 2
  fi

  if ! grep -q 'Allow-Http-From-Internet' "$outfile"; then
    echo "ERROR: scoped what-if missing intended HTTP rule addition." >&2
    exit 2
  fi

  local forbidden
  forbidden='Microsoft\.Compute/virtualMachines|Microsoft\.DBforPostgreSQL|Microsoft\.KeyVault|Microsoft\.Storage/storageAccounts|Microsoft\.Authorization/roleAssignments|Microsoft\.Network/networkInterfaces|Microsoft\.Network/publicIPAddresses|Microsoft\.Network/virtualNetworks|Microsoft\.Network/privateDnsZones'
  if grep -E "^  [+~=-] .*(${forbidden})" "$outfile" >/dev/null 2>&1; then
    echo "ERROR: scoped what-if proposes unrelated resource changes." >&2
    grep -E "^  [+~=-] .*(${forbidden})" "$outfile" >&2 || true
    exit 2
  fi

  if grep -E '^  [-] ' "$outfile" | grep -q 'securityRules'; then
    echo "ERROR: scoped what-if proposes security rule deletion." >&2
    exit 2
  fi

  echo "scoped_what_if_validation=PASS"
}

echo "Compiling scoped invite-production NSG Bicep template..."
az bicep build --file "$AZ_TEMPLATE" --stdout >/dev/null

if [ "$MODE" = "validate" ]; then
  echo "Scoped NSG Bicep compilation passed. No Azure resources were changed."
  exit 0
fi

assert_target_lock
assert_live_nsg_baseline

if [ "$MODE" = "apply" ] && [ "$CONFIRMATION" != "$APPLY_CONFIRMATION_TOKEN" ]; then
  echo "ERROR: scoped NSG apply requires --confirm $APPLY_CONFIRMATION_TOKEN." >&2
  exit 2
fi

echo "Validating scoped ARM deployment..."
az deployment group validate \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --template-file "$AZ_TEMPLATE" \
  --output none

WHAT_IF_OUT="$(mktemp "${TMPDIR:-/tmp}/parkio-invite-nsg-whatif.XXXXXX.txt")"
cleanup() { rm -f -- "$WHAT_IF_OUT"; }
trap cleanup EXIT HUP INT TERM

echo "Running scoped NSG what-if..."
az deployment group what-if \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --template-file "$AZ_TEMPLATE" \
  --result-format FullResourcePayloads \
  --exclude-change-type Ignore NoChange \
  > "$WHAT_IF_OUT"

cat "$WHAT_IF_OUT"
validate_scoped_what_if_output "$WHAT_IF_OUT"

if [ "$MODE" = "what-if" ]; then
  echo "Scoped NSG what-if completed. No Azure resources were changed."
  exit 0
fi

echo "Applying scoped invite-production NSG deployment..."
az deployment group create \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --template-file "$AZ_TEMPLATE" \
  --output none

echo "Scoped NSG deployment completed."
az network nsg rule list \
  --resource-group "$RESOURCE_GROUP" \
  --nsg-name "$NSG_NAME" \
  --query "[].{name:name,priority:priority,protocol:protocol,access:access,source:sourceAddressPrefix,destPort:destinationPortRange}" \
  -o table
