# Account Number Sequence Custom Modules

## Canonical module

Use **`custom/accountnumber/starter`** for all deployments. It is the only account-number module included in the custom Docker image (`custom/docker/build.gradle`).

Features:
- Hi-lo allocation via `CLIENT:GLOBAL`
- Optional gap reuse (`account-number-reuse-gaps`)
- Background gap pool refill and orphan CLAIM reclaim
- Scheduled gap maintenance via Fineract batch job (tenant `job` table)

## Non-canonical / legacy modules (do not deploy)

| Path | Status |
|------|--------|
| `custom/fynarfin/accountnumber` | Legacy Fynarfin copy; hi-lo only, not in Docker image |
| `custom/accountnumber/service` | Extracted library, superseded by `starter` |
| `custom/accountnumber/an_format` | Alternate packaging experiment |

Do not add these modules to `custom/docker/build.gradle`. Operational SQL scripts live under `custom/accountnumber/starter/scripts/`.

## Operations

1. Run `starter/scripts/01-enable-client-sequence.sql`
2. Enable gap reuse: `starter/scripts/04-enable-gap-reuse.sql` (or set `account-number-reuse-gaps` in UI)
3. Optional manual prefill: `starter/scripts/06-prefill-gap-pool.sql` (repeat until `gaps_inserted_this_run = 0`)

Gap pool also refills automatically after client creates (async) and via the **Account Number Gap Maintenance** batch job.

## Batch job: Account Number Gap Maintenance

| Setting | Value |
|---------|-------|
| Job name (UI / `job` table) | `Account Number Gap Maintenance` |
| Default cron | `0 0/5 * * * ?` (every 5 minutes) |
| Requires | `fineract.mode.batch-manager-enabled=true` on at least one instance |

### Control via API

| Action | Endpoint |
|--------|----------|
| List jobs | `GET /fineract-provider/api/v1/jobs` |
| Disable job | `PUT /fineract-provider/api/v1/jobs/{jobId}` with `{ "active": false }` |
| Change schedule | `PUT /fineract-provider/api/v1/jobs/{jobId}` with `{ "cronExpression": "0 0/10 * * * ?" }` |
| Run now | `POST /fineract-provider/api/v1/jobs/{jobId}?command=executeJob` |
| Run history | `GET /fineract-provider/api/v1/jobs/{jobId}/runhistory` |

API-only replicas do not schedule this job; only the batch-manager pod runs it per tenant.

## Configuration (`c_configuration`)

| Name | Default | Purpose |
|------|---------|---------|
| `account-number-reuse-gaps` | disabled | Master switch for gap reuse |
| `account-number-gap-pool-batch-size` | 100 | Max GAP rows inserted per discovery pass |
| `account-number-gap-pool-low-watermark` | 10 | Refill when pool count drops below this |
| `account-number-gap-claim-timeout-minutes` | 60 | Reclaim orphaned CLAIM rows after N minutes |
| `startup-gap-scan-fill-size` | 2000 | Suffix range scanned per discovery window |
| `account-number-gap-refill-max-windows` | 50 | Safety cap on empty scan windows per refill run |

## Multi-instance deployment

- Scheduled gap maintenance runs only on the **batch-manager** instance (standard Fineract job scheduling).
- Client-create triggered refill uses a per-tenant MariaDB `GET_LOCK` so only one pod refills at a time cluster-wide.
- Do not rely on JVM-local locks alone for background refill across replicas.
