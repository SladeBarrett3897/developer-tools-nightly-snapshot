package dev.learning.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Properties;

public record SnapshotSettings(String bucket, ZoneId zone, int hour, int port, String releaseChannel) {
    public static SnapshotSettings load(Path path) throws IOException {
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path)) { values.load(reader); }
        return new SnapshotSettings(env("SNAPSHOT_BUCKET", values.getProperty("snapshot.bucket")),
                ZoneId.of(env("SNAPSHOT_ZONE", values.getProperty("snapshot.zone"))),
                Integer.parseInt(env("SNAPSHOT_HOUR", values.getProperty("snapshot.hour"))),
                Integer.parseInt(env("SNAPSHOT_PORT", values.getProperty("snapshot.port"))),
                env("SNAPSHOT_RELEASE_CHANNEL", values.getProperty("snapshot.releaseChannel")));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
