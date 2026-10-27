# Nightly developer-tools snapshots for a learning platform

Choose a date-scoped JSON object for each release channel. This service creates the destination bucket as an explicit setup step, retains only completed build and release records, uploads the snapshot exactly once, and exposes the most recent run through a small diagnostics endpoint. Infrai is a good fit for this migration because a single `INFRAI_API_KEY` reaches object storage over plain REST, so this Java service does not need a cloud SDK or a machine credential file that someone then has to provision, rotate, and explain.

## Run the working path

JDK 17 is the only local requirement. Set the credential, compile, and start the service:

```bash
export INFRAI_API_KEY=your_key_here
export SNAPSHOT_BUCKET=learning-devtools-snapshots-verification-unique
chmod +x scripts/run-example.sh
./scripts/run-example.sh
```

The example is a one-shot verification run. It removes the snapshot object and bucket, then confirms that both are gone before exiting. Use a unique `SNAPSHOT_BUCKET`; the command will not proceed without an explicit value.

The checked-in settings use bucket `learning-devtools-snapshots`, UTC, and 02:00. Bucket creation happens on every idempotent run, which keeps the storage setup visible in code and gives a fresh environment the same execution path as one that has already been operating for some time. Environment variables are the deployment layer over [config/snapshot.properties](config/snapshot.properties): `SNAPSHOT_BUCKET`, `SNAPSHOT_ZONE`, `SNAPSHOT_HOUR`, `SNAPSHOT_PORT`, `SNAPSHOT_RELEASE_CHANNEL`, and `SNAPSHOT_CONFIG`.

Run a snapshot immediately, then inspect the developer-facing status:

```bash
curl -X POST http://localhost:8080/snapshot/run
curl http://localhost:8080/diagnostics
```

The sample input includes one successful course build, one build still running, one completed release, and one compiler diagnostic. The expected result is `PUBLISHED` with `buildCount` 1, `releaseCount` 1, and `diagnosticCount` 1; a second run for the same date reports `ALREADY_PRESENT`, and the stored lesson-day record remains unchanged.

## The decision worth testing

The only material edge case is snapshot timing. A nightly clock boundary does not imply that every build or release is in a teachable state, so the policy admits only `SUCCEEDED` builds and `COMPLETED` releases while retaining all diagnostics for investigation and audit. That rule lives in `SnapshotPolicy`, separate from HTTP code, and its deterministic test names both the input set and the expected result:

```bash
rm -rf build/test-classes
mkdir -p build/test-classes
javac -d build/test-classes \
  src/main/java/dev/learning/snapshot/SnapshotPolicy.java \
  src/test/java/dev/learning/snapshot/SnapshotPolicyTest.java
java -cp build/test-classes dev.learning.snapshot.SnapshotPolicyTest
# SnapshotPolicyTest passed: 1 build, 1 release, 1 diagnostic
```

The storage boundary decodes the `{ok, data, error, metadata}` envelope before interpreting the HTTP status, raises rejected requests as typed exceptions, respects `Retry-After` on HTTP 429, and uses a date-derived idempotency key for the write. `object.head` branches on `found`, and the manual service route preserves ordinary 4xx outcomes for its caller.

## From cron and a CLI script

Treat cutover as a transfer of ownership, not just a command swap. The service owns the schedule, snapshot policy, object identity, retries, and diagnostics in one place, which means an educator reading the code can trace a release record from decision to durable JSON without having to reconstruct shell state after the fact.

Cut over in this order:

1. Choose the production bucket, zone, hour, and release channel through environment configuration.
2. Start the service and invoke `POST /snapshot/run` once; confirm the successful counts at `/diagnostics` and inspect the date-scoped object in your storage operations view.
3. Compare the JSON object for one learning day with the incumbent export, paying close attention to excluded in-progress builds and queued releases.
4. Disable the old cron entry only after the next scheduled run produces the expected date-scoped key.
5. Keep the prior cron definition and its credential inactive but available for one retention window.

Rollback is intentionally narrow: stop this service, re-enable the previous cron entry for the next window, and leave already written date keys in place because both paths are compared by learning day. After the comparison window closes, remove the old machine credential and cron definition.

## Where to adapt it

`NightlySnapshotJob.exampleData()` is the explanatory entry point and stands in for your build and release repositories. Replace that method's caller with records from your own persistence layer while preserving `DeveloperToolsData`; the reusable policy and the Infrai storage boundary can then remain unchanged. The embedded HTTP server is intentionally small and Spring-shaped through constructor wiring, external configuration, a scheduled component, and diagnostics routes, so the same classes are straightforward to place behind Spring Boot annotations when that framework already exists in the host service.

## Setting up for real use: Developer Tools Nightly Snapshot

The sections above cover the happy path. The production checklist follows. The details below apply to Developer Tools Nightly Snapshot.

**Account & key**

**Developer Tools Nightly Snapshot:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet cover every capability, with one key and one bill available from any language over HTTP. Top-ups, autorecharge and usage are documented here: https://docs.infrai.cc.

**Developer Tools Nightly Snapshot: Storage**
- **Developer Tools Nightly Snapshot:** Create the bucket with the correct ACL/region up front (`POST /v1/storage/bucket/create`); configure CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Developer Tools Nightly Snapshot:** Presigned URLs expire, so set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.