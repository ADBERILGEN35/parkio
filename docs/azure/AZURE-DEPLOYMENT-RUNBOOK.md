# Azure Hosted-Beta Deployment Runbook

This is a single-operator procedure. It does not authorize deployment. Run commands from Azure Cloud Shell unless a local authenticated Azure CLI is available. Replace every angle-bracket placeholder; never paste secrets into command history.

## Operator variables

```bash
export AZURE_SUBSCRIPTION_ID='<AZURE_SUBSCRIPTION_ID>'
export RESOURCE_GROUP='<RESOURCE_GROUP>'
export REGION='westeurope'
export VM_NAME='<VM_NAME>'
export SSH_USER='<SSH_USER>'
export ADMIN_CIDR='<ADMIN_PUBLIC_IP>/32'
export VNET_NAME='parkio-beta-vnet'
export SUBNET_NAME='parkio-beta-subnet'
export NSG_NAME='parkio-beta-nsg'
export NIC_NAME='parkio-beta-nic'
export PUBLIC_IP_NAME='parkio-beta-ip'
export DATA_DISK_NAME='parkio-beta-data'
```

## Portal and CLI map

| Step | Azure Portal path | CLI command / verification |
|---|---|---|
| Subscription | Subscriptions -> selected subscription -> Overview | `az account list -o table`; `az account set --subscription "$AZURE_SUBSCRIPTION_ID"`; `az account show -o table` |
| Region/SKU | Virtual machines -> Create -> Size | `az vm list-skus -l "$REGION" --size Standard_D4as_v5 --all -o table` |
| Resource group | Resource groups -> Create | `az group create -n "$RESOURCE_GROUP" -l "$REGION"`; `az group show -n "$RESOURCE_GROUP"` |
| Budget | Cost Management + Billing -> Budgets -> Add | `az consumption budget create --budget-name parkio-30d --amount 180 --category Cost --time-grain Monthly --start-date <YYYY-MM-01> --end-date <YYYY-MM-01>`; verify `az consumption budget list -o table` if supported |
| VNet/subnet | Virtual networks -> Create | commands below; verify `az network vnet show -g "$RESOURCE_GROUP" -n "$VNET_NAME"` |
| NSG | Network security groups -> Create -> Inbound rules | commands below; verify `az network nsg rule list -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" -o table` |
| Public IP | Public IP addresses -> Create | `az network public-ip create ...`; verify `az network public-ip show ... --query ipAddress -o tsv` |
| VM/NIC | Virtual machines -> Create -> Azure virtual machine | commands below; verify `az vm show -d -g "$RESOURCE_GROUP" -n "$VM_NAME" -o table` |
| Data disk | VM -> Disks -> Create and attach a new disk | commands below; verify `az vm show ... --query storageProfile.dataDisks` |
| Cost | Cost Management -> Cost analysis | `az costmanagement query --scope /subscriptions/$AZURE_SUBSCRIPTION_ID ...` if supported; otherwise Portal is authoritative |
| Cleanup | Resource groups -> Delete resource group | `az group delete -n "$RESOURCE_GROUP" --yes --no-wait` after export/verification |

Budget CLI support varies by account type and extension. If it fails, mark CLI budget creation BLOCKED and create actual/forecast alerts at USD 50, 100, 150, 175, and 190 in Portal before the VM.

## 1. Account, quota, and price gate

```bash
az login
az account list -o table
az account set --subscription "$AZURE_SUBSCRIPTION_ID"
az account show --query '{name:name,id:id,state:state,tenantId:tenantId}' -o yaml
az vm list-usage -l "$REGION" -o table
az vm list-skus -l "$REGION" --size Standard_D4as_v5 --all -o table
```

Portal: **Subscriptions -> Usage + quotas** and **Cost Management -> Credits**. Record exact credit expiry and available credit. Stop if the SKU is restricted, credit is below USD 180, or the portal estimate exceeds the cost model.

## 2. Resource group, VNet, NSG, static IP

```bash
az group create -n "$RESOURCE_GROUP" -l "$REGION"
az network vnet create -g "$RESOURCE_GROUP" -n "$VNET_NAME" \
  --address-prefixes 10.20.0.0/16 \
  --subnet-name "$SUBNET_NAME" --subnet-prefixes 10.20.1.0/24
az network nsg create -g "$RESOURCE_GROUP" -n "$NSG_NAME" -l "$REGION"
az network nsg rule create -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
  -n AllowSshOperator --priority 100 --access Allow --direction Inbound \
  --protocol Tcp --source-address-prefixes "$ADMIN_CIDR" --source-port-ranges '*' \
  --destination-address-prefixes '*' --destination-port-ranges 22
az network nsg rule create -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
  -n AllowHttp --priority 110 --access Allow --direction Inbound \
  --protocol Tcp --source-address-prefixes Internet --source-port-ranges '*' \
  --destination-address-prefixes '*' --destination-port-ranges 80
az network nsg rule create -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
  -n AllowHttps --priority 120 --access Allow --direction Inbound \
  --protocol Tcp --source-address-prefixes Internet --source-port-ranges '*' \
  --destination-address-prefixes '*' --destination-port-ranges 443
az network nsg rule create -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" \
  -n AllowHttp3 --priority 121 --access Allow --direction Inbound \
  --protocol Udp --source-address-prefixes Internet --source-port-ranges '*' \
  --destination-address-prefixes '*' --destination-port-ranges 443
az network public-ip create -g "$RESOURCE_GROUP" -n "$PUBLIC_IP_NAME" \
  --sku Standard --allocation-method Static --version IPv4 --location "$REGION"
export PUBLIC_IP="$(az network public-ip show -g "$RESOURCE_GROUP" -n "$PUBLIC_IP_NAME" --query ipAddress -o tsv)"
az network nic create -g "$RESOURCE_GROUP" -n "$NIC_NAME" \
  --vnet-name "$VNET_NAME" --subnet "$SUBNET_NAME" \
  --network-security-group "$NSG_NAME" --public-ip-address "$PUBLIC_IP_NAME"
```

No Load Balancer, Application Gateway, NAT Gateway, Bastion, private endpoint, or second subnet is required.

## 3. VM and disks

```bash
az vm create -g "$RESOURCE_GROUP" -n "$VM_NAME" \
  --nics "$NIC_NAME" --image Ubuntu2404 \
  --size Standard_D4as_v5 --storage-sku StandardSSD_LRS \
  --os-disk-size-gb 64 --admin-username "$SSH_USER" \
  --ssh-key-values '<PATH_TO_PUBLIC_SSH_KEY>' \
  --authentication-type ssh --security-type TrustedLaunch \
  --enable-secure-boot true --enable-vtpm true
az disk create -g "$RESOURCE_GROUP" -n "$DATA_DISK_NAME" \
  --size-gb 128 --sku StandardSSD_LRS --location "$REGION"
az vm disk attach -g "$RESOURCE_GROUP" --vm-name "$VM_NAME" \
  --name "$DATA_DISK_NAME" --lun 0
az vm show -d -g "$RESOURCE_GROUP" -n "$VM_NAME" -o table
az vm list-ip-addresses -g "$RESOURCE_GROUP" -n "$VM_NAME" -o table
```

If Trusted Launch or the image alias is unavailable, stop and resolve in Portal; do not silently weaken it. Do not use Spot.

## 4. DNS before TLS

In Hostinger, create A records with TTL 300:

```text
app.parkio.dev   -> <PUBLIC_IP>
api.parkio.dev   -> <PUBLIC_IP>
media.parkio.dev -> <PUBLIC_IP>
```

Web and mobile hosted-beta builds both use `https://api.parkio.dev/api/v1`. The mobile artifact gate rejects the retired `beta-api.parkio.dev` hostname.

## 5. SSH and data-disk mount

```bash
ssh -i <PATH_TO_PRIVATE_SSH_KEY> "$SSH_USER@$PUBLIC_IP"
sudo lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINTS
sudo test -b /dev/disk/azure/scsi1/lun0
sudo mkfs.ext4 -L parkio-data /dev/disk/azure/scsi1/lun0
sudo mkdir -p /var/lib/docker
DATA_UUID="$(sudo blkid -s UUID -o value /dev/disk/azure/scsi1/lun0)"
echo "UUID=$DATA_UUID /var/lib/docker ext4 defaults,nofail 0 2" | sudo tee -a /etc/fstab
sudo mount -a
findmnt /var/lib/docker
```

`mkfs.ext4` is destructive. Run it only on the newly attached empty LUN after verifying size/path.

## 6. OS hardening and packages

```bash
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get -y upgrade
sudo apt-get install -y ca-certificates curl git jq openssl ufw unattended-upgrades fail2ban gnupg
sudo tee /etc/ssh/sshd_config.d/99-parkio.conf >/dev/null <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
MaxAuthTries 3
AllowUsers <SSH_USER>
EOF
sudo sshd -t
sudo systemctl reload ssh
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow from <ADMIN_PUBLIC_IP> to any port 22 proto tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw --force enable
sudo ufw status verbose
sudo dpkg-reconfigure -f noninteractive unattended-upgrades
sudo timedatectl status
```

Replace `<SSH_USER>` inside the heredoc before execution. Keep the current SSH session open and verify a second key-auth session before closing it.

## 7. Docker Engine and Compose

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
newgrp docker
docker version
docker compose version
docker info
findmnt /var/lib/docker
```

Stop if Compose is older than 2.24.4.

## 8. Repository checkout and secrets

```bash
sudo mkdir -p /opt/parkio
sudo chown "$USER:$USER" /opt/parkio
git clone <REPOSITORY_URL> /opt/parkio
cd /opt/parkio
git checkout <RELEASE_TAG_OR_COMMIT>
git status --short
install -m 0640 -o "$USER" -g "$USER" docker/.env.azure-hosted-beta.example docker/.env.azure-hosted-beta
sudo mkdir -m 0700 -p /root/parkio-secrets
sudo openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /root/parkio-secrets/jwt-private.pem
sudo openssl rand -base64 48 > /root/parkio-secrets/gateway.secret
sudo openssl rand -base64 48 > /root/parkio-secrets/waitlist.secret
for name in auth gateway user parking media gamification notification moderation analytics aivalidation redis minio grafana backup; do
  sudo openssl rand -base64 32 > "/root/parkio-secrets/$name.secret"
done
sudoedit /opt/parkio/docker/.env.azure-hosted-beta
chmod 0640 /opt/parkio/docker/.env.azure-hosted-beta
```

Populate the env through the editor/password manager without echoing values. Keep the canonical hosts, distinct DB/gateway/waitlist secrets, tracing disabled, and no placeholders. Alertmanager is excluded by this profile, so no webhook is required.

## 9. Preflight, render, and dry runs

```bash
cd /opt/parkio
export PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
export PARKIO_ENV_FILE=docker/.env.azure-hosted-beta
./scripts/preflight-hosted-beta.sh
./scripts/validate-hosted-beta-compose.sh
time ./scripts/deploy-hosted-beta.sh --dry-run --operator "$USER"
./scripts/backup-hosted-beta.sh --dry-run
./scripts/restore-hosted-beta.sh \
  --manifest backup-artifacts/backup-current.json --dry-run
```

The restore dry run can only use the manifest created by the backup dry run. Review `deploy-artifacts/compose-config.rendered.yml` for secret values before copying it anywhere; treat it as sensitive.

## 10. Deploy

Do not expose DNS to testers yet. Record actual cold build time:

```bash
cd /opt/parkio
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
PARKIO_GATEWAY_URL=https://api.parkio.dev \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
time ./scripts/deploy-hosted-beta.sh --operator "$USER"
```

The selected profile targets exactly 32 services and never starts Alertmanager, Loki, Promtail, or Tempo. Do not run the inactive `azure-disabled-observability` profile on this VM size.

## 11. Health, TLS, and exposure checks

```bash
curl -fsS https://app.parkio.dev/ >/dev/null
curl -fsS https://api.parkio.dev/actuator/health | jq .
curl -fsS https://api.parkio.dev/api/v1/auth/.well-known/jwks.json | jq '.keys | length'
curl -fsSI https://app.parkio.dev/
sudo ss -lntup
docker compose --env-file docker/.env.azure-hosted-beta \
  -f docker/docker-compose.yml -f docker/docker-compose.apps.yml \
  -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml \
  -f docker/docker-compose.azure-hosted-beta.yml ps
free -h
df -h / /var/lib/docker
docker stats --no-stream
```

From an external machine, ports 8080-8089, 3000, 5432-5441, 6379, 9000-9001, 9090, 9093, 9308, 3100, 3200, 3310, and 29092 must fail. Do not paste a mass scanner against third-party targets; test only this owned IP.

## 12. Authenticated, waitlist, media, monitoring smoke

```bash
PARKIO_GATEWAY_URL=https://api.parkio.dev \
PARKIO_REAL_USER_EMAIL='<BETA_TEST_EMAIL>' \
PARKIO_REAL_USER_PASSWORD='<BETA_TEST_PASSWORD>' \
PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED=1 \
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/smoke-hosted-beta.sh

curl -sS -o /tmp/waitlist-response.json -w '%{http_code}\n' \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"<TEST_WAITLIST_EMAIL>\",\"consent\":true,\"consentTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"source\":\"parkio.dev-landing\"}" \
  https://api.parkio.dev/api/v1/waitlist
rm -f /tmp/waitlist-response.json

ssh -L 3000:localhost:3000 -L 9090:localhost:9090 "$SSH_USER@$PUBLIC_IP"
```

Expected waitlist status is 202. Do not open real intake until legal/privacy text, deletion handling, and the currently modified frontend waitlist test are green. Perform one real image upload through web/mobile and prove ClamAV, MinIO signed GET, and media metadata; the generic smoke script does not prove the write path.

## 13. Backup, restore, rollback

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/backup-hosted-beta.sh
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/restore-hosted-beta.sh \
  --manifest backup-artifacts/backup-current.json --dry-run
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/restore-drill.sh --service parking --keep-backups
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta PARKIO_ENV_FILE=docker/.env.azure-hosted-beta ./scripts/rollback-hosted-beta.sh \
  --manifest deploy-artifacts/current.json --dry-run
```

Copy the encrypted backup set, backup/deploy manifests, and restore-drill evidence to an operator-controlled machine immediately. A live rollback test requires a second known-good release manifest and retained images; schedule it before opening the beta. Repository estimate: 3-8 minutes when images exist locally. A database restore is not a release rollback and must not be used for ordinary bad code.

## 14. Cost and final gate

```bash
az vm show -d -g "$RESOURCE_GROUP" -n "$VM_NAME" --query '{power:powerState,size:hardwareProfile.vmSize,ip:publicIps}' -o yaml
az network nsg rule list -g "$RESOURCE_GROUP" --nsg-name "$NSG_NAME" -o table
az resource list -g "$RESOURCE_GROUP" -o table
az consumption budget list -o table
```

Portal: **Cost Management -> Cost analysis -> group by Resource**. GO only when:

- all required health/auth/waitlist/upload/security checks pass,
- 24 hours show no OOM, swap pressure, restart loop, sustained CPU throttling, or disk alarm,
- available memory stays at least 2 GiB during normal beta and startup is observed,
- one off-VM backup and one disposable restore drill pass,
- forecast stays below USD 180 through exit day,
- only named testers are invited.

Any kernel/container OOM, sustained swap, less than 1 GiB available memory, repeated unhealthy service, failed backup/restore, public internal port, invalid TLS, or forecast above USD 190 is an immediate NO-GO: close access, back up, deallocate, and decide whether to resize or exit.
