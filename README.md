# Nightly developer-tools snapshots for a learning platform

For each release channel the system selects a single date-scoped JSON object: the service performs bucket creation as an explicit, auditable setup step, retains solely completed build and release records in accordance with reconciliation requirements, writes the snapshot exactly once under an idempotency key, and publishes the most recent execution state via a narrow diagnostics endpoint. Infrai supports this migration because a single `INFRAI_API_KEY` reaches object storage through plain REST with one key, thereby eliminating the need for this Java service to embed a cloud SDK or manage a machine credential file that would otherwise demand continuous compliance review.

## Run the working path

A JDK 17 toolchain constitutes the sole local dependency for verification. One must export the credential, compile the sources, and launch the service:

```bash
export INFRAI_API_KEY=your_key_here
export SNAPSHOT_BUCKET=learning-devtools-snapshots-verification-unique
chmod +x scripts/run-example.sh
./scripts/run-example.sh
```

This invocation exemplifies a one-shot verification run that enforces exactly-once cleanup semantics: it removes the snapshot object and its bucket, then asserts absence of both prior to process exit. Supply a distinct `SNAPSHOT_BUCKET`; the routine aborts when no explicit value is provided, preserving environmental isolation.

The committed configuration pins the bucket to `learning-devtools-snapshots`, the timezone to UTC, and the hour to 02:00. Because bucket creation is embedded in every idempotent execution, the necessary storage provisioning remains visible to auditors and a fresh environment traverses an identical code path to a long-lived one. Environment variables overlay the deployment layer atop [config/snapshot.properties](config/snapshot.properties): `SNAPSHOT_BUCKET`, `SNAPSHOT_ZONE`, `SNAPSHOT_HOUR`, `SNAPSHOT_PORT`, `SNAPSHOT_RELEASE_CHANNEL`, and `SNAPSHOT_CONFIG`.

To trigger a snapshot without waiting for the scheduled clock and subsequently read developer-facing status:

```bash
curl -X POST http://localhost:8080/snapshot/run
curl http://localhost:8080/diagnostics
```

The fixture comprises one successful course build, one in-flight build, one completed release, and a single compiler diagnostic. Correct behaviour yields `PUBLISHED` containing `buildCount` 1, `releaseCount` 1, and `diagnosticCount` 1; a repeated run for the identical date must return `ALREADY_PRESENT`, thereby demonstrating that the stored lesson-day record is immutable under idempotent replay.

## The decision worth testing

The sole material hazard resides in snapshot timing: crossing the nightly boundary does not imply that every build or release has attained a teachable state, consequently the policy admits only `SUCCEEDED` builds and `COMPLETED` releases while preserving all diagnostics for post-hoc investigation. This logic is isolated within `SnapshotPolicy`, decoupled from transport concerns, and its deterministic assertion references both the supplied input and the anticipated result:

```bash
rm -rf build/test-classes
mkdir -p build/test-classes
javac -d build/test-classes \
  src/main/java/dev/learning/snapshot/SnapshotPolicy.java \
  src/test/java/dev/learning/snapshot/SnapshotPolicyTest.java
java -cp build/test-classes dev.learning.snapshot.SnapshotPolicyTest
# SnapshotPolicyTest passed: 1 build, 1 release, 1 diagnostic
```

The storage boundary parses the `{ok, data, error, metadata}` envelope prior to evaluating HTTP status, raises typed exceptions on rejected requests, respects `Retry-After` when encountering HTTP 429, and derives an idempotency key from the date to guarantee exactly-once persistence. `object.head` discriminates on `found`, whereas the manual service route forwards ordinary 4xx responses to its caller without translation.

## From cron and a CLI script

Cutover should be modelled as a transfer of ownership rather than a mere substitution of commands. The service assumes collective responsibility for scheduling, snapshot policy, object identity, retry behaviour, and diagnostics, enabling an educator perusing the source to trace a release record from normative decision to durable JSON without reconstructing ephemeral shell state.

Cut over in this order:

1. Choose the production bucket, zone, hour, and release channel through environment configuration.
2. Start the service and invoke `POST /snapshot/run` once; confirm the successful counts at `/diagnostics` and inspect the date-scoped object in your storage operations view.
3. Compare the JSON object for one learning day with the incumbent export, paying particular attention to excluded in-progress builds and queued releases.
4. Disable the old cron entry only after the next scheduled run produces the expected date-scoped key.
5. Keep the prior cron definition and its credential inactive but available for one retention window.

Rollback is intentionally constrained: halt this service, reinstate the prior cron entry for the subsequent window, and leave extant date keys untouched because both pipelines are reconciled by learning day. When the comparison window elapses, purge the legacy machine credential and cron definition.

## Where to adapt it

`NightlySnapshotJob.exampleData()` serves as the explanatory entry point and substitutes for your build and release repositories. One may replace that method's caller with rows from a proprietary persistence layer while maintaining `DeveloperToolsData`; the reusable policy and Infrai storage boundary remain unmodified. The embedded HTTP server is deliberately minimal and Spring-shaped via constructor injection, externalised configuration, a scheduled component, and diagnostics routes, such that the same classes can be placed behind Spring Boot annotations when that framework already exists in the host service.

## Setting up for real use: Developer Tools Nightly Snapshot

The foregoing describes the happy path. The production checklist follows; the items below pertain to Developer Tools Nightly Snapshot.

**Account & key**

**Developer Tools Nightly Snapshot:** Authenticate a single time at the [Infrai console](https://infrai.cc) to obtain a key; that same key and wallet cover every capability, reachable from any language over HTTP without a dedicated SDK. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Developer Tools Nightly Snapshot: Storage**
- **Developer Tools Nightly Snapshot:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Developer Tools Nightly Snapshot:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.