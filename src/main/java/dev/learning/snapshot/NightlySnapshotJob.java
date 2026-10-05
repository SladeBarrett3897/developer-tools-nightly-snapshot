package dev.learning.snapshot;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public final class NightlySnapshotJob {
    public enum Outcome { PUBLISHED, ALREADY_PRESENT }
    public record RunResult(Outcome outcome, String objectKey, int buildCount, int releaseCount,
                            int diagnosticCount, Instant finishedAt) {}

    private final InfraiStorage storage;
    private final SnapshotPolicy policy;
    private final SnapshotSettings settings;
    private final Clock clock;

    public NightlySnapshotJob(InfraiStorage storage, SnapshotPolicy policy, SnapshotSettings settings, Clock clock) {
        this.storage = storage;
        this.policy = policy;
        this.settings = settings;
        this.clock = clock;
    }

    public RunResult run(SnapshotPolicy.DeveloperToolsData input) throws IOException, InterruptedException {
        storage.createBucket(settings.bucket());
        LocalDate day = LocalDate.now(clock.withZone(settings.zone()));
        SnapshotPolicy.SnapshotPlan plan = policy.plan(day, settings.releaseChannel(), input);
        if (storage.objectExists(settings.bucket(), plan.objectKey())) return result(Outcome.ALREADY_PRESENT, plan);
        storage.putJson(settings.bucket(), plan.objectKey(), toJson(day, plan), "snapshot-" + day);
        return result(Outcome.PUBLISHED, plan);
    }

    public RunResult runOnceAndCleanup(SnapshotPolicy.DeveloperToolsData input) throws IOException, InterruptedException {
        storage.createBucket(settings.bucket());
        LocalDate day = LocalDate.now(clock.withZone(settings.zone()));
        SnapshotPolicy.SnapshotPlan plan = policy.plan(day, settings.releaseChannel(), input);
        boolean objectCreated = false;
        try {
            if (storage.objectExists(settings.bucket(), plan.objectKey())) {
                throw new IllegalStateException("Cleanup run requires an empty, uniquely named bucket");
            }
            storage.putJson(settings.bucket(), plan.objectKey(), toJson(day, plan), "snapshot-" + day);
            objectCreated = true;
            return result(Outcome.PUBLISHED, plan);
        } finally {
            if (objectCreated) {
                storage.deleteObject(settings.bucket(), plan.objectKey());
                if (storage.objectExists(settings.bucket(), plan.objectKey())) {
                    throw new IllegalStateException("Snapshot object still exists after cleanup");
                }
            }
            storage.deleteBucket(settings.bucket());
            if (storage.bucketExists(settings.bucket())) {
                throw new IllegalStateException("Snapshot bucket still exists after cleanup");
            }
        }
    }

    private RunResult result(Outcome outcome, SnapshotPolicy.SnapshotPlan plan) {
        return new RunResult(outcome, plan.objectKey(), plan.data().builds().size(), plan.data().releases().size(),
                plan.data().diagnostics().size(), clock.instant());
    }

    private static String toJson(LocalDate day, SnapshotPolicy.SnapshotPlan plan) {
        String builds = plan.data().builds().stream().map(b -> "{\"courseId\":" + InfraiStorage.quote(b.courseId())
                + ",\"buildId\":" + InfraiStorage.quote(b.buildId()) + ",\"status\":" + InfraiStorage.quote(b.status()) + "}")
                .reduce((a, b) -> a + "," + b).orElse("");
        String releases = plan.data().releases().stream().map(r -> "{\"courseId\":" + InfraiStorage.quote(r.courseId())
                + ",\"version\":" + InfraiStorage.quote(r.version()) + ",\"state\":" + InfraiStorage.quote(r.state()) + "}")
                .reduce((a, b) -> a + "," + b).orElse("");
        String diagnostics = plan.data().diagnostics().stream().map(d -> "{\"component\":" + InfraiStorage.quote(d.component())
                + ",\"severity\":" + InfraiStorage.quote(d.severity()) + ",\"message\":" + InfraiStorage.quote(d.message()) + "}")
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"snapshotDate\":" + InfraiStorage.quote(day.toString()) + ",\"buildEvents\":[" + builds
                + "],\"releaseOperations\":[" + releases + "],\"diagnostics\":[" + diagnostics + "]}";
    }

    public static SnapshotPolicy.DeveloperToolsData exampleData() {
        return new SnapshotPolicy.DeveloperToolsData(
                List.of(new SnapshotPolicy.BuildEvent("java-foundations", "build-184", "SUCCEEDED"),
                        new SnapshotPolicy.BuildEvent("data-literacy", "build-912", "RUNNING")),
                List.of(new SnapshotPolicy.ReleaseOperation("java-foundations", "2026.09", "COMPLETED")),
                List.of(new SnapshotPolicy.Diagnostic("lesson-compiler", "INFO", "All modules compiled")));
    }
}
