# Delivery state contract — PARKIO-Y03A

## Classification

| Outcome | Queue status | Dedup status | Meaning |
|---------|--------------|--------------|---------|
| Confirmed success | `delivered` | `delivered` | HTTP 200 + ok/non-error body |
| Transient / 429 | `retry` | admitted (`queued`) | Bounded retry; Retry-After honored |
| Permanent / rejected | `dead` | `dead` | DLT row; no auto retry |
| Request not sent (conn refused) | `retry`→`dead` | as above | Transient, not ambiguous |
| Potentially accepted, response lost | `retry` (ambiguous) then `delivery_unknown` | `delivery_unknown` | **Never** `delivered` |

## Ambiguous policy

1. Classify as `TransportClass.AMBIGUOUS` (timeout, remote disconnect after send).
2. Bounded retry (`max_attempts`) with **documented duplicate risk**.
3. After exhaustion → terminal `delivery_unknown` + DLT reason `delivery_unknown:…`.
4. Metrics: `slack_biz_ambiguous_retry_total`, `slack_biz_delivery_unknown_total`.
5. Operator: `--list-unknown` / `--resolve-unknown` (`accept_as_delivered` | `requeue` | `discard`).

## Dedup vs delivery

Admission (`queued`/`in_flight`/`retry`) blocks re-enqueue independently of final outcome.  
`already_delivered` only for confirmed delivery.  
`already_uncertain` for `delivery_unknown`.

## Exactly-once

**Not promised.** Incoming webhooks cannot support exactly-once.
