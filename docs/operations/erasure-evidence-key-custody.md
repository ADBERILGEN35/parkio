# Erasure evidence keys: custody, rotation and retirement (format v2)

Procedure only. This document contains no key and generates none. Who holds the keys, how often
they rotate, and where trust documents live on hosts are operator decisions that are not yet
recorded. Contract: `docs/operations/recovery-evidence-contract.md` §7 "Trust and key rotation".

## Roles

| Role | Holds | Does |
|---|---|---|
| Key custodian (operator to be named) | every producer key | generates, distributes, rotates and retires keys; keeps the record of `keyId` windows |
| Producer: auth-service object-lock store | a trust document and the id of its signing key | signs evidence with one key; verifies what it reads with the whole trust document |
| Consumer: recovery operator and tooling | a trust document with the keys it must verify | verifies evidence; never reads keys or the database identity from the evidence |

## Trust document

JSON, read from `parkio.privacy.account-erasure.durable-store.object-lock.trust-file`
(`PARKIO_ERASURE_STORE_TRUST_FILE`). The signing key is selected by `producer-key-id`
(`PARKIO_ERASURE_STORE_PRODUCER_KEY_ID`).

```json
{
  "format": "parkio-erasure-evidence-trust",
  "version": 1,
  "databaseIdentity": "postgresql:<system_identifier>:<datname>",
  "keys": [
    {
      "keyId": "<key id>",
      "producerId": "<producer id>",
      "notBefore": "<ISO-8601 UTC instant>",
      "notAfter": "<ISO-8601 UTC instant, optional>",
      "retired": false,
      "keyHex": "<secret: at least 32 random bytes as hex>"
    }
  ]
}
```

- The document holds HMAC secrets, so it is secret material. Store it with the same care as other
  service secrets: readable only by the service user (for example mode 0400 on a mounted file),
  never committed, never pasted into tickets or logs. The service and the verifiers print key
  ids, never secrets.
- The service has no default key. Fixture keys under
  `services/auth-service/src/test/resources/durable-erasure-evidence/v2` and in tests are
  synthetic, not secrets, and must never be used outside tests.

## Database identity

Run this on the auth database the producer writes to (no superuser needed on PostgreSQL 16):

```sql
SELECT 'postgresql:' || system_identifier::text || ':' || current_database() FROM pg_control_system();
```

- The identity stays the same across physical replication and failover. A new cluster gets a
  new identity: a fresh initdb, or a logical restore into a new cluster. Its evidence is then a
  new lineage and needs a trust document pinned to the new identity.
- The service refuses to start with a trust document pinned to another identity.
- Consumers pin the identity they expect. They never take it from the evidence or from a
  restored copy.

## Generating a key

On a trusted workstation, take at least 32 bytes from a CSPRNG, for example
`openssl rand -hex 32`. Choose a `keyId` that is unique for all time, for example
`auth-erasure-2026-10a`. Record the `keyId`, the `producerId`, the window and the custodian. The
secret goes only into trust documents.

## Rotation

1. Add the new key to the trust document with `notBefore` = T, a future switch time.
2. Distribute the updated document to every consumer first. Consumers refuse the new key's
   objects before T (`producer key not yet valid`).
3. At or after T, distribute the document to the producer. Set `producer-key-id` to the new key
   and restart. Startup refuses a signing key outside its window or retired, and a signing key
   that is missing from the document.
4. Set the old key's `notAfter` to T in every copy. Do **not** retire the old key: the evidence
   it signed is write-once and still needs it to verify. The producer refuses to sign with a key
   past its `notAfter`, and writes fail as `DURABLE_RECORDING_UNAVAILABLE`, with requests
   staying `PENDING_DURABLE` for the retry worker, until the producer runs with a valid key.
5. Check the result with a recovery verification over the bucket using the consumer document:
   the verdict is `ACCEPT_ISOLATED`, and objects carry both key ids.

## Retirement (revocation)

Set `retired: true` only when a key must no longer be trusted, for example on suspected
compromise. Every object the key signed is then refused (`retired producer key`). Because the
objects cannot be re-signed, recovery through those objects is `BLOCKED`. Treat the period as an
unknown tail (contract §2): keep a restored copy unexposed, and handle the affected erasures
through the documented re-request path. Rotate to a new key before retiring the compromised one.

## Not decided here

- The key custodian.
- The rotation cadence.
- The host location and mount mechanism of trust documents.
- The distribution channel to consumers.
- The response owner for a compromised key.
