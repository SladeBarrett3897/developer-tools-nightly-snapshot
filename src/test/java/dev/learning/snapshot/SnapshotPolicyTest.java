package dev.learning.snapshot;

import java.time.LocalDate;
import java.util.List;

public final class SnapshotPolicyTest {
    public static void main(String[] args) {
        SnapshotPolicy policy = new SnapshotPolicy();
        SnapshotPolicy.DeveloperToolsData input = new SnapshotPolicy.DeveloperToolsData(
                List.of(new SnapshotPolicy.BuildEvent("algebra", "b-1", "SUCCEEDED"),
                        new SnapshotPolicy.BuildEvent("algebra", "b-2", "RUNNING")),
                List.of(new SnapshotPolicy.ReleaseOperation("algebra", "v4", "COMPLETED"),
                        new SnapshotPolicy.ReleaseOperation("biology", "v7", "QUEUED")),
                List.of(new SnapshotPolicy.Diagnostic("compiler", "WARN", "One optional link is stale")));
        SnapshotPolicy.SnapshotPlan plan = policy.plan(LocalDate.parse("2026-09-08"), "stable", input);
        check(plan.objectKey().equals("developer-tools/stable/2026-09-08.json"), "date-scoped key");
        check(plan.data().builds().size() == 1 && plan.excludedBuilds() == 1, "only completed builds enter a lesson snapshot");
        check(plan.data().releases().size() == 1 && plan.excludedReleases() == 1, "only completed releases enter a lesson snapshot");
        check(plan.data().diagnostics().size() == 1, "diagnostics remain visible");
        System.out.println("SnapshotPolicyTest passed: 1 build, 1 release, 1 diagnostic");
    }

    private static void check(boolean condition, String decision) {
        if (!condition) throw new AssertionError(decision);
    }
}
