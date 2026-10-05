package dev.learning.snapshot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class DeveloperToolsSnapshotService {
    public static void main(String[] args) throws Exception {
        SnapshotSettings settings = SnapshotSettings.load(Path.of(System.getenv().getOrDefault("SNAPSHOT_CONFIG", "config/snapshot.properties")));
        NightlySnapshotJob job = new NightlySnapshotJob(new InfraiStorage(System.getenv("INFRAI_API_KEY")),
                new SnapshotPolicy(), settings, Clock.system(settings.zone()));
        if (args.length == 1 && args[0].equals("--once-cleanup")) {
            NightlySnapshotJob.RunResult result = job.runOnceAndCleanup(NightlySnapshotJob.exampleData());
            System.out.println("Snapshot verification completed and cleaned up: " + result.objectKey());
            return;
        }
        AtomicReference<String> diagnostics = new AtomicReference<>("{\"status\":\"waiting-for-first-run\"}");
        AtomicInteger latestStatus = new AtomicInteger(202);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runnable task = () -> {
            try {
                NightlySnapshotJob.RunResult result = job.run(NightlySnapshotJob.exampleData());
                diagnostics.set("{\"status\":\"healthy\",\"outcome\":" + InfraiStorage.quote(result.outcome().name())
                        + ",\"objectKey\":" + InfraiStorage.quote(result.objectKey()) + ",\"buildCount\":" + result.buildCount()
                        + ",\"releaseCount\":" + result.releaseCount() + ",\"diagnosticCount\":" + result.diagnosticCount() + "}");
                latestStatus.set(202);
            } catch (InfraiStorage.InfraiException e) {
                diagnostics.set("{\"status\":\"request-rejected\",\"code\":" + InfraiStorage.quote(e.code()) + "}");
                latestStatus.set(e.clientStatus());
            } catch (Exception e) {
                diagnostics.set("{\"status\":\"job-error\",\"message\":" + InfraiStorage.quote(e.getMessage()) + "}");
                latestStatus.set(502);
            }
        };
        long initialDelay = delayUntilNextRun(settings);
        scheduler.scheduleAtFixedRate(task, initialDelay, Duration.ofDays(1).toSeconds(), TimeUnit.SECONDS);
        startDiagnosticsServer(settings.port(), diagnostics, latestStatus, task);
        System.out.println("Snapshot service listening on http://localhost:" + settings.port() + "/diagnostics");
    }

    static long delayUntilNextRun(SnapshotSettings settings) {
        ZonedDateTime now = ZonedDateTime.now(settings.zone());
        ZonedDateTime next = now.withHour(settings.hour()).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next).toSeconds();
    }

    private static void startDiagnosticsServer(int port, AtomicReference<String> diagnostics,
                                               AtomicInteger latestStatus, Runnable task) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/diagnostics", exchange -> respond(exchange, 200, diagnostics.get()));
        server.createContext("/snapshot/run", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { respond(exchange, 405, "{\"error\":\"POST required\"}"); return; }
            task.run();
            respond(exchange, latestStatus.get(), diagnostics.get());
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
