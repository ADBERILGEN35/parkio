# Parkio Mobile Local Runtime

This runbook is for local Android runtime validation on Windows with Docker Desktop, Android Emulator, Expo Go, and the Parkio backend.

## Stable topology

- Docker Desktop runs the backend containers on Windows using the Linux engine.
- The API gateway is exposed on the Windows host at `http://localhost:8080` and `http://127.0.0.1:8080`.
- The Android Emulator runs on Windows. For host services, Android uses `10.0.2.2`, so the mobile API base is `http://10.0.2.2:8080/api/v1`.
- Expo Metro should run from Windows PowerShell, not WSL, for Android runtime proof.
- Use Metro port `8090` because the backend auth service publishes `8081` locally.
- Avoid `adb reverse` for this setup. Use `10.0.2.2` for backend access and `exp://10.0.2.2:8090` for Expo Go.

## Start Docker backend

From Windows PowerShell:

```powershell
cd C:\Users\ADBERILGEN\Documents\parkio
docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml up -d
```

If the full stack reports a mount/share error from an auxiliary container, verify the core services and start the gateway directly:

```powershell
docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml ps auth-service media-service gateway-service redis kafka clamav
docker compose -f docker/docker-compose.yml -f docker/docker-compose.apps.yml up -d gateway-service
```

Verify the gateway from Windows:

```powershell
curl.exe -i http://localhost:8080/api/v1/auth/.well-known/jwks.json
curl.exe -i http://127.0.0.1:8080/api/v1/auth/.well-known/jwks.json
```

Both should return `HTTP/1.1 200 OK` with JWKS JSON.

## Start Android emulator

From Windows PowerShell:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Pixel_8
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

The emulator should appear as `emulator-5554 device`.

The emulator reaches the backend through:

```text
http://10.0.2.2:8080/api/v1
```

Do not use `localhost` from inside Android for the backend; Android `localhost` is the emulator itself.

## Mobile environment

For Android Emulator runtime, `frontend/apps/mobile/.env.local` must include:

```dotenv
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080/api/v1
```

If overriding from PowerShell, set the same value before starting Expo:

```powershell
$env:EXPO_PUBLIC_API_BASE_URL = "http://10.0.2.2:8080/api/v1"
```

## Install frontend dependencies on Windows

From Windows PowerShell:

```powershell
cd C:\Users\ADBERILGEN\Documents\parkio\frontend
$env:CI = "true"
corepack pnpm install --frozen-lockfile
```

## Start Expo Metro on Windows

From Windows PowerShell:

```powershell
cd C:\Users\ADBERILGEN\Documents\parkio\frontend
$env:EXPO_PUBLIC_API_BASE_URL = "http://10.0.2.2:8080/api/v1"
$env:EXPO_NO_TELEMETRY = "1"
corepack pnpm --filter @parkio/mobile exec expo start --host lan --port 8090 --clear
```

Open Expo Go on the emulator with:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 shell am start -a android.intent.action.VIEW -d exp://10.0.2.2:8090
```

## Common fixes

- If Windows curl cannot reach `localhost:8080`, the gateway is not healthy or not published. Check `docker compose ... ps gateway-service` and start `gateway-service` directly.
- If WSL curl cannot reach Docker-published ports but Windows curl works, keep runtime validation in Windows PowerShell. Do not mix WSL Metro with Windows emulator for native proof.
- If Expo tries port `8081`, force `--port 8090`; `8081` is already used by the local backend auth service.
- If Expo Go shows `Cannot connect to Expo CLI`, restore emulator networking and reopen `exp://10.0.2.2:8090`.
- Do not use airplane mode to test upload retry in Expo Go: it can break the Expo dev-session transport before the app can render its own offline retry UI. Use a dev build for full offline/background upload validation if this scenario must be proven natively.
- If Docker rebuild downloads Gradle and times out, do not rebuild for mobile runtime validation unless code changed. Use existing images and `up -d`.

## Verification commands

From `C:\Users\ADBERILGEN\Documents\parkio\frontend`:

```powershell
corepack pnpm --filter @parkio/mobile test
corepack pnpm --filter @parkio/mobile lint
corepack pnpm --filter @parkio/mobile typecheck
corepack pnpm --filter @parkio/mobile run doctor
```
