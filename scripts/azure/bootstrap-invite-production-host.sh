#!/usr/bin/env bash
# Idempotent base-host bootstrap for the isolated invite-production VM.

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: host bootstrap must run as root." >&2
  exit 2
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg git jq openssl postgresql-client \
  docker.io docker-compose-v2 unattended-upgrades

if ! command -v az >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://packages.microsoft.com/keys/microsoft.asc \
    | gpg --dearmor --yes -o /etc/apt/keyrings/microsoft.gpg
  chmod a+r /etc/apt/keyrings/microsoft.gpg
  . /etc/os-release
  printf 'Types: deb\nURIs: https://packages.microsoft.com/repos/azure-cli/\nSuites: %s\nComponents: main\nArchitectures: amd64\nSigned-by: /etc/apt/keyrings/microsoft.gpg\n' \
    "$VERSION_CODENAME" > /etc/apt/sources.list.d/azure-cli.sources
  apt-get update -qq
  apt-get install -y -qq azure-cli
fi

DATA_DEVICE=/dev/disk/azure/scsi1/lun0
for _ in $(seq 1 30); do
  [ -b "$DATA_DEVICE" ] && break
  sleep 2
done
if [ ! -b "$DATA_DEVICE" ]; then
  echo "ERROR: expected Azure data disk $DATA_DEVICE was not found." >&2
  exit 3
fi

if [ -z "$(blkid -s TYPE -o value "$DATA_DEVICE" 2>/dev/null || true)" ]; then
  mkfs.ext4 -F -L parkio-data "$DATA_DEVICE" >/dev/null
fi

install -d -m 0755 /opt/parkio-data
DATA_UUID="$(blkid -s UUID -o value "$DATA_DEVICE")"
if ! grep -q "UUID=$DATA_UUID " /etc/fstab; then
  printf 'UUID=%s /opt/parkio-data ext4 defaults,nofail 0 2\n' "$DATA_UUID" >> /etc/fstab
fi
mountpoint -q /opt/parkio-data || mount /opt/parkio-data

systemctl stop docker.service docker.socket 2>/dev/null || true
install -d -m 0711 /opt/parkio-data/docker
install -d -m 0755 /etc/docker
cat > /etc/docker/daemon.json <<'JSON'
{
  "data-root": "/opt/parkio-data/docker",
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "20m",
    "max-file": "5"
  },
  "live-restore": true
}
JSON
systemctl daemon-reload
systemctl enable --now docker
usermod -aG docker parkioops

install -d -o parkioops -g parkioops -m 0750 \
  /opt/parkio \
  /opt/parkio/certs \
  /opt/parkio/deploy-artifacts \
  /opt/parkio-data/parkio \
  /var/lib/parkio/evidence \
  /var/backups/parkio
install -m 0644 /etc/ssl/certs/ca-certificates.crt /opt/parkio/certs/azure-postgres-root.crt

az login --identity --allow-no-subscriptions --output none

echo "Invite-production host bootstrap completed."
echo "docker=$(docker --version)"
echo "compose=$(docker compose version)"
echo "azureCli=$(az version --query '"azure-cli"' --output tsv | tr -d '\r')"
echo "postgresClient=$(psql --version)"
echo "dataMount=$(findmnt -n -o TARGET,SOURCE,FSTYPE /opt/parkio-data)"
