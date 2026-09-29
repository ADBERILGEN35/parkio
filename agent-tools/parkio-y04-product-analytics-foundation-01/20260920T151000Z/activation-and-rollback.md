# Y04 — Activation and rollback

## Activate (non-prod first)

1. Provision PostHog project (EU/US host) — **out of this package**  
2. Set env: vendor enabled + key + host (web Vite / mobile Expo public)  
3. Deploy client builds containing Y04  
4. Users must opt in via Preferences analytics toggle  
5. Confirm session replay remains off (no replay package; payload flag false)

## Rollback

1. Set vendor enabled flag to `false` (or remove key) — immediate stop of outbound  
2. Optionally force consent denied via remote config later (not in Y04)  
3. Clients with consent granted but vendor off keep LocalCapture/null behavior only  
4. No server-side analytics microservice to roll back

## Safety

- No production mutation in this package  
- No real-user tracking activated  
- Real vendor ingestion = **NOT_EXECUTED**
