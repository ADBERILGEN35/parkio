#!/usr/bin/env bash
# Provision the isolated PROD-DEPLOY-01A Azure foundation.
#
# The default action only compiles the Bicep template. --what-if creates the
# empty (free) resource group when needed and previews the deployment. Paid
# resources require both --apply and --confirm PROD-DEPLOY-01A.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEMPLATE="$ROOT/infra/azure/invite-production/main.bicep"
RESOURCE_GROUP="rg-parkio-invite-production-we"
LOCATION="westeurope"
DEPLOYMENT_NAME="prod-deploy-01a-foundation"
MODE="validate"
CONFIRMATION=""
SSH_PUBLIC_KEY_FILE="${PARKIO_INVITE_SSH_PUBLIC_KEY_FILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --validate) MODE="validate"; shift ;;
    --what-if) MODE="what-if"; shift ;;
    --apply) MODE="apply"; shift ;;
    --confirm) CONFIRMATION="${2:-}"; shift 2 ;;
    --ssh-public-key-file) SSH_PUBLIC_KEY_FILE="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

for tool in az openssl; do
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

echo "Compiling invite-production Bicep template..."
az bicep build --file "$AZ_TEMPLATE" --stdout >/dev/null

if [ "$MODE" = "validate" ]; then
  echo "Bicep compilation passed. No Azure resources were created."
  exit 0
fi

if [ -z "$SSH_PUBLIC_KEY_FILE" ] || [ ! -f "$SSH_PUBLIC_KEY_FILE" ]; then
  echo "ERROR: --ssh-public-key-file must identify an existing OpenSSH public key." >&2
  exit 2
fi

if [ "$MODE" = "apply" ] && [ "$CONFIRMATION" != "PROD-DEPLOY-01A" ]; then
  echo "ERROR: paid-resource apply requires --confirm PROD-DEPLOY-01A." >&2
  exit 2
fi

if ! az account show --query id -o tsv >/dev/null; then
  echo "ERROR: Azure CLI is not authenticated." >&2
  exit 2
fi

OPERATOR_OBJECT_ID="$(az ad signed-in-user show --query id -o tsv)"
SSH_PUBLIC_KEY="$(tr -d '\r\n' < "$SSH_PUBLIC_KEY_FILE")"
if [ -z "$OPERATOR_OBJECT_ID" ] || [ -z "$SSH_PUBLIC_KEY" ]; then
  echo "ERROR: operator object ID and SSH public key must be non-empty." >&2
  exit 2
fi

# Azure CLI is a Windows executable in the supported WSL operator environment,
# so create secure transient parameters under Windows TEMP. Native Linux uses
# TMPDIR. The password is never echoed and ARM treats it as securestring.
TEMP_BASE="${TMPDIR:-/tmp}"
if [[ "$AZ_PATH" == /mnt/c/* ]] && command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
  WINDOWS_TEMP="$(cmd.exe /d /c 'echo %TEMP%' 2>/dev/null | tr -d '\r')"
  TEMP_BASE="$(wslpath -u "$WINDOWS_TEMP")"
fi
SECURE_TEMP_DIR="$(mktemp -d "$TEMP_BASE/parkio-invite-params.XXXXXX")"
chmod 700 "$SECURE_TEMP_DIR"
cleanup() {
  if [ -n "${SECURE_TEMP_DIR:-}" ] && [[ "$SECURE_TEMP_DIR" == "$TEMP_BASE"/parkio-invite-params.* ]]; then
    rm -rf -- "$SECURE_TEMP_DIR"
  fi
}
trap cleanup EXIT HUP INT TERM

if [ "$MODE" = "apply" ]; then
  POSTGRES_ADMIN_PASSWORD="Aa1!$(openssl rand -hex 30)"
else
  POSTGRES_ADMIN_PASSWORD="Aa1!WhatIfOnlyPassword000000000000000000000000000000"
fi

PARAMETERS_FILE="$SECURE_TEMP_DIR/parameters.json"
umask 077
printf '%s\n' \
  '{' \
  '  "$schema": "https://schema.management.azure.com/schemas/2019-04-01/deploymentParameters.json#",' \
  '  "contentVersion": "1.0.0.0",' \
  '  "parameters": {' \
  "    \"operatorObjectId\": { \"value\": \"$OPERATOR_OBJECT_ID\" }," \
  "    \"sshPublicKey\": { \"value\": \"$SSH_PUBLIC_KEY\" }," \
  "    \"administratorLoginPassword\": { \"value\": \"$POSTGRES_ADMIN_PASSWORD\" }" \
  '  }' \
  '}' > "$PARAMETERS_FILE"

AZ_PARAMETERS_FILE="$PARAMETERS_FILE"
if [[ "$AZ_PATH" == /mnt/c/* ]] && command -v wslpath >/dev/null 2>&1; then
  AZ_PARAMETERS_FILE="$(wslpath -w "$PARAMETERS_FILE")"
fi

if [ "$(az group exists --name "$RESOURCE_GROUP" | tr -d '\r')" != "true" ]; then
  echo "Creating dedicated resource group $RESOURCE_GROUP (no paid resources yet)..."
  az group create \
    --name "$RESOURCE_GROUP" \
    --location "$LOCATION" \
    --tags application=parkio environment=invite-production package=PROD-DEPLOY-01A \
    --output none
fi

echo "Validating ARM deployment..."
az deployment group validate \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --template-file "$AZ_TEMPLATE" \
  --parameters "@$AZ_PARAMETERS_FILE" \
  --output none

if [ "$MODE" = "what-if" ]; then
  echo "Running redacted ARM what-if..."
  az deployment group what-if \
    --name "$DEPLOYMENT_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --template-file "$AZ_TEMPLATE" \
    --parameters "@$AZ_PARAMETERS_FILE" \
    --result-format ResourceIdOnly
  echo "What-if completed. No paid resources were created."
  exit 0
fi

echo "Creating isolated invite-production foundation..."
az deployment group create \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --template-file "$AZ_TEMPLATE" \
  --parameters "@$AZ_PARAMETERS_FILE" \
  --output none

unset POSTGRES_ADMIN_PASSWORD

echo "Invite-production foundation deployment completed. Non-secret identities:"
az deployment group show \
  --name "$DEPLOYMENT_NAME" \
  --resource-group "$RESOURCE_GROUP" \
  --query "properties.outputs.{environment:environment.value,vmName:vmName.value,vmSize:vmSize.value,postgresqlName:postgresqlName.value,postgresqlFqdn:postgresqlFqdn.value,postgresqlPublicAccess:postgresqlPublicAccess.value,backupRetentionDays:postgresqlBackupRetentionDays.value,highAvailability:postgresqlHighAvailability.value,keyVault:keyVaultName.value,backupStorage:backupStorageAccount.value,backupContainer:backupContainer.value}" \
  --output table
