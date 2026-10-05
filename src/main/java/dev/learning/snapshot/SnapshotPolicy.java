package dev.learning.snapshot;

import java.time.LocalDate;
import java.util.List;

public final class SnapshotPolicy {
    public record BuildEvent(String courseId, String buildId, String status) {}
    public record ReleaseOperation(String courseId, String version, String state) {}
    public record Diagnostic(String component, String severity, String message) {}
    public record DeveloperToolsData(List<BuildEvent> builds, List<ReleaseOperation> releases,
                                     List<Diagnostic> diagnostics) {}
    public record SnapshotPlan(String objectKey, DeveloperToolsData data, int excludedBuilds,
                               int excludedReleases) {}

    public SnapshotPlan plan(LocalDate learningDay, String releaseChannel, DeveloperToolsData input) {
        List<BuildEvent> builds = input.builds().stream()
                .filter(event -> event.status().equals("SUCCEEDED"))
                .toList();
        List<ReleaseOperation> releases = input.releases().stream()
                .filter(operation -> operation.state().equals("COMPLETED"))
                .toList();
        String key = "developer-tools/" + releaseChannel + "/" + learningDay + ".json";
        return new SnapshotPlan(key, new DeveloperToolsData(builds, releases, input.diagnostics()),
                input.builds().size() - builds.size(), input.releases().size() - releases.size());
    }
}
