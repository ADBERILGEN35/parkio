# Azure DNS and TLS Plan

## DNS ownership

`parkio.dev` and its nameservers remain at Hostinger. Azure DNS is unnecessary. Create records in **Hostinger hPanel -> Domains -> DNS / Nameservers -> DNS records** only after the Azure Standard static IPv4 exists.

| Hostname | Type / value / TTL | Public | TLS | Access requirement |
|---|---|---|---|---|
| `parkio.dev` | unchanged Hostinger records | yes | existing Hostinger | landing site; no Azure change |
| `app.parkio.dev` | A -> `<PUBLIC_IP>`, TTL 300 | yes | Caddy/Let's Encrypt | SPA; required by current Caddyfile |
| `api.parkio.dev` | A -> `<PUBLIC_IP>`, TTL 300 | yes | Caddy/Let's Encrypt | public API through gateway auth/rate limits |
| `media.parkio.dev` | A -> `<PUBLIC_IP>`, TTL 300 | yes | Caddy/Let's Encrypt | only private-bucket presigned GET URLs through MinIO |
| `grafana.parkio.dev` | **do not create** | no | none | SSH local forward to `localhost:3000` |
| `status.parkio.dev` | optional CNAME to external status provider | optional | provider | protect admin; provider must be outside VM failure domain |

Do not create AAAA records unless an Azure IPv6 public IP and matching NSG path are deliberately provisioned and tested. A stale AAAA can make ACME/browser traffic fail intermittently. If CAA records exist, allow Let's Encrypt (`letsencrypt.org`).

## Required environment mapping

```dotenv
PARKIO_WEB_DOMAIN=app.parkio.dev
PARKIO_DOMAIN=api.parkio.dev
PARKIO_MEDIA_DOMAIN=media.parkio.dev
PARKIO_WEB_UPSTREAM=web:80
PARKIO_CORS_ALLOWED_ORIGINS=https://app.parkio.dev
VITE_API_BASE_URL=https://api.parkio.dev/api/v1
PARKIO_MEDIA_STORAGE_ENDPOINT=http://minio:9000
PARKIO_MEDIA_STORAGE_PUBLIC_ENDPOINT=https://media.parkio.dev
```

The frontend API URL is baked into the image. Any change requires a web rebuild. The public media endpoint must exactly match the request Host because MinIO SigV4 signs it.

## TLS issuance sequence

1. Allocate the static IP and create A records.
2. Verify authoritative DNS from two resolvers:

   ```bash
   dig +short A app.parkio.dev @1.1.1.1
   dig +short A api.parkio.dev @8.8.8.8
   dig +short A media.parkio.dev @1.1.1.1
   ```

3. Confirm NSG/UFW allow 80/TCP and 443/TCP. Allow 443/UDP only if HTTP/3 is retained.
4. Start Caddy once DNS is correct. Preserve `caddy-data`; repeated deletion/reissuance can hit ACME limits.
5. Inspect without exposing secrets:

   ```bash
   docker compose --env-file docker/.env.azure-hosted-beta \
     -f docker/docker-compose.yml -f docker/docker-compose.apps.yml \
     -f docker/docker-compose.images.yml -f docker/docker-compose.hosted-beta.yml \
     -f docker/docker-compose.azure-hosted-beta.yml \
     logs --tail=100 caddy
   curl -fsSI https://app.parkio.dev/
   curl -fsS https://api.parkio.dev/actuator/health
   openssl s_client -connect api.parkio.dev:443 -servername api.parkio.dev </dev/null 2>/dev/null \
     | openssl x509 -noout -subject -issuer -dates
   ```

Expected headers include HSTS and `X-Content-Type-Options`; the SPA also receives CSP, Referrer-Policy, Permissions-Policy, and frame restrictions. WebSocket traffic is supported by Caddy's `reverse_proxy` automatically, but no repository-required public WebSocket endpoint was identified; verify only if a feature begins using it.

## Rollback

Before Azure deletion, lower TTL to 300 at least one TTL window ahead. Remove `app/api/media` A records or restore their prior values. Do not point them to Hostinger unless Hostinger actually serves those origins. Verify with authoritative `dig`, then delete the Azure public IP only after traffic and backup export are complete.
