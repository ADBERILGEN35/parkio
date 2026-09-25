# Y04 — Active-time contract

## Signals

| Platform | Foreground | Focus | Interaction |
|----------|------------|-------|-------------|
| Web | `document.visibilityState === 'visible'` | `window` focus/blur | pointerdown / keydown |
| Mobile-v2 | `AppState === 'active'` | root focus via layout | (screen enter resets idle) |

Clock: monotonic (`performance.now` / injectable). Occurrence timestamps on events use wall clock for ordering offline, **not** for duration math.

## Policy

| Rule | Implementation |
|------|----------------|
| Idle | Stop accumulating after **60s** without interaction |
| Heartbeat | **Disabled (0 ms)** by default |
| Background | Suspend immediately |
| Screen transition | Exit prior screen → emit summary → enter next (no double count) |
| WebView map on RN | Duration attributed to **one** RN screen (`map`); bridge must not emit a second parallel timer |
| Crash / no exit | Next summary may set `incomplete=true` |
| Checkpoints | Monotonic `checkpointSeq`; out-of-order / duplicate ignored; no inflation |
| Offline | Queue retains `occurredAtMs`; flush marks `deferred=true` |

## Volume / accuracy trade-off

15s heartbeat was **rejected as default**: local accumulation + exit summary gives sufficient screen active-time for funnel dashboards at much lower event volume. Operators may set `heartbeatIntervalMs` later if product requires mid-screen checkpoints; not required for Y04 acceptance.
