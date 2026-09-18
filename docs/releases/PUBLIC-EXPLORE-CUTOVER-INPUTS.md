# Controlled public explore deployment inputs

01E-A1 is source + CI only. Nothing in this document authorizes deployment,
public exposure, Hostinger changes, or completion of a human gate.

## Single explicit mode

The invite-production workflow accepts `public_explore_mode`:

| Input | Parking / gateway | Web build | Public sources |
| --- | --- | --- | --- |
| missing or `off` | false | false | empty |
| `izum-readonly` | true | true | exactly `izum` |

Unknown modes, alternate/multiple sources and split-layer configurations fail.
The registration runtime and build modes remain `closed` in both states.
Do not set the three explore flags independently; the dispatch applicator emits
them from this mode, after the Key Vault renderer creates the per-job tmpfs env.

ON also requires `public_explore_authorization=GOOGLE-STARTUP-REAPPLY-01E-B`.
This dedicated, non-secret acknowledgement does not replace the existing
`invite-production` environment required-reviewer approval. No human approval
is encoded as a boolean or inferred from source, CI success, or this token.

The public edge remains `invite_edge_mode=public`, `invite_acme_authorized=true`,
`registration_mode=closed`. Its existing, separate
`cutover_authorization=PROD-DEPLOY-01B-03E-B` contract is unchanged; it does NOT
authorize explore. OFF needs no **explore** authorization value.

## Guard order and evidence

1. On the hosted build runner, validate dispatch mode/authorization before the
   production reviewer job can become eligible (`needs: build-images`).
2. Apply the same inputs to the CI-only env and validate a real merged Compose
   dry-run manifest. Production DNS/Azure lookup is not part of this offline run.
3. After external reviewer approval, render production secrets into tmpfs and
   apply the same dispatch inputs. Keep all existing live edge/DNS guards.
4. Revalidate the intended mode against Compose before release staging and
   again when emitting the secret-free manifest (`publicExploreMode`, exact
   runtime flags, web build args, closed registration). A mode mismatch stops.
5. Build web with that env's `VITE_PUBLIC_EXPLORE_ENABLED`, then use the same
   release inputs for runtime. Compose rollout is not atomic: public exposure
   is not certified until internal/external acceptance passes. Marketing stays
   unchanged until then. Cached frontend cannot override gateway/parking OFF.

## Explicit feature rollback (not executed)

Use the reviewed `action=deploy` path on the exact approved SHA, with
`public_explore_mode=off` and **empty** `public_explore_authorization`; retain
the unchanged public-edge inputs and closed registration above. This rebuilds
the web bundle OFF and renders gateway/parking OFF with empty public sources.
No DNS, Caddy configuration, database, Flyway, or Hostinger change is needed.

Do not use the legacy no-rebuild manifest rollback to change a build-time web
flag: an ON web image stays ON even if its Compose build args say false. A full
historical-image rollback is a separate reviewed recovery action and must use
an independently verified OFF artifact. API flags remain authoritative while
cached clients expire; already cached public responses cannot be recalled.

Founder LinkedIn, legal IZUM enablement, MapTiler origin/quota, Hostinger access,
and production deploy approval all remain pending. No provider or freshness
policy changes accompany this contract.
