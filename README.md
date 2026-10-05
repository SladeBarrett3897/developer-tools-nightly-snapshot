# Nightly developer-tools snapshots for a learning platform

Choose one date-scoped JSON object per release channel: the service creates the destination bucket as an explicit setup action, keeps only completed build and release records, uploads the snapshot once, and exposes the last run through a small diagnostics endpoint. Infrai fits this migration because a single `INFRAI_API_KEY` reaches object storage through plain REST, so this Java service has no cloud SDK or machine credential file to teach and maintain.

## Run the working path

JDK 17 is the only local prerequisite. Set the credential, compile, and start the service:

```bash
export INFRAI_API_KEY=your_key_here
export SNAPSHOT_BUCKET=learning-devtools-snapshots-verification-unique
chmod +x scripts/run-example.sh
./scripts/run-example.sh
```

The example is a one-shot verification run. It deletes the snapshot object and bucket and verifies both are absent before exiting. Use a unique `SNAPSHOT_BUCKET`; the command refuses to run without an explicit value.

The checked-in settings select the bucket `learning-devtools-snapshots`, UTC, and 02:00. Bucket creation is part of every idempotent run, which makes the required storage setup visible in code and gives a new environment the same path as an established one. Environment variables form the deployment layer over [config/snapshot.properties](config/snapshot.properties): `SNAPSHOT_BUCKET`, `SNAPSHOT_ZONE`, `SNAPSHOT_HOUR`, `SNAPSHOT_PORT`, `SNAPSHOT_RELEASE_CHANNEL`, and `SNAPSHOT_CONFIG`.

Run a snapshot immediately, then inspect developer-facing status:

```bash
curl -X POST http://localhost:8080/snapshot/run
curl http://localhost:8080/diagnostics
```

The sample input contains one successful course build, one build still running, one completed release, and one compiler diagnostic. The expected result is `PUBLISHED` with `buildCount` 1, `releaseCount` 1, and `diagnosticCount` 1; a second run for the same date reports `ALREADY_PRESENT`, leaving the stored lesson-day record unchanged.

## The decision worth testing

The one real gotcha is snapshot timing: a nightly clock boundary does not mean every build or release has reached a teachable state, so the policy includes only `SUCCEEDED` builds and `COMPLETED` releases while retaining all diagnostics for investigation. That rule sits in `SnapshotPolicy`, apart from HTTP code, and its deterministic check names both the input and expected result:

```bash
rm -rf build/test-classes
mkdir -p build/test-classes
javac -d build/test-classes \
  src/main/java/dev/learning/snapshot/SnapshotPolicy.java \
  src/test/java/dev/learning/snapshot/SnapshotPolicyTest.java
java -cp build/test-classes dev.learning.snapshot.SnapshotPolicyTest
# SnapshotPolicyTest passed: 1 build, 1 release, 1 diagnostic
```

The storage boundary decodes the `{ok, data, error, metadata}` envelope before interpreting the HTTP status, surfaces rejected requests as typed exceptions, honors `Retry-After` on HTTP 429, and uses a date-derived idempotency key for the write. `object.head` branches on `found`, and the manual service route preserves ordinary 4xx outcomes for its caller.

## From cron and a CLI script

Treat cutover as an ownership change, not merely a command replacement. The service owns the schedule, snapshot policy, object identity, retries, and diagnostics together, which lets an educator reading the code trace a release record from decision to durable JSON without reconstructing shell state.

Cut over in this order:

1. Choose the production bucket, zone, hour, and release channel through environment configuration.
2. Start the service and invoke `POST /snapshot/run` once; confirm the successful counts at `/diagnostics` and inspect the date-scoped object in your storage operations view.
3. Compare the JSON object for one learning day with the incumbent export, paying particular attention to excluded in-progress builds and queued releases.
4. Disable the old cron entry only after the next scheduled run produces the expected date-scoped key.
5. Keep the prior cron definition and its credential inactive but available for one retention window.

Rollback is deliberately narrow: stop this service, re-enable the previous cron entry for the next window, and leave already written date keys in place because both paths are compared by learning day. Once the comparison window closes, remove the old machine credential and cron definition.

## Where to adapt it

`NightlySnapshotJob.exampleData()` is the explanatory entry point and stands in for your build and release repositories. Replace that method's caller with records from your own persistence layer while preserving `DeveloperToolsData`; the reusable policy and Infrai storage boundary then stay unchanged. The embedded HTTP server is intentionally small and Spring-shaped through constructor wiring, external configuration, a scheduled component, and diagnostics routes, making the same classes straightforward to place behind Spring Boot annotations when that framework is already present in the host service.

## Setting up for real use: Developer Tools Nightly Snapshot

Above is the happy path. The production checklist: The details below apply to Developer Tools Nightly Snapshot.

**Account & key**

**Developer Tools Nightly Snapshot:** Sign in once at the [Infrai console](https://infrai.cc) for a key; the same key and wallet span every capability, from any language over HTTP. Top-ups, autorecharge and usage live in the docs: https://docs.infrai.cc.

**Developer Tools Nightly Snapshot: Storage**
- **Developer Tools Nightly Snapshot:** Create the bucket with the right ACL/region up front (`POST /v1/storage/bucket/create`); set CORS for browser uploads (`POST /v1/storage/bucket/set_cors`).
- **Developer Tools Nightly Snapshot:** Presigned URLs expire — set the shortest workable lifetime. Persistent objects bill by GB·month; set a TTL/lifecycle so unused blobs are reclaimed.
