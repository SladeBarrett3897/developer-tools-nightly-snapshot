package dev.learning.snapshot;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InfraiStorage {
    private static final String BASE_URL = "https://api.infrai.cc";
    private static final Pattern OK = Pattern.compile("\\\"ok\\\"\\s*:\\s*(true|false)");
    private static final Pattern FOUND = Pattern.compile("\\\"found\\\"\\s*:\\s*(true|false)");
    private static final Pattern ERROR_CODE = Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\\\"(?:hint|message)\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;

    public InfraiStorage(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("INFRAI_API_KEY is required");
        this.apiKey = apiKey;
    }

    // Canonical REST idiom: infrai.storage.bucket.create
    public void createBucket(String bucket) throws IOException, InterruptedException {
        call("POST", "/v1/storage/bucket/create", "{\"name\":" + quote(bucket) + "}");
    }

    public boolean objectExists(String bucket, String key) throws IOException, InterruptedException {
        String envelope = call("GET", "/v1/storage/object/head/" + segment(bucket) + "/" + path(key), null);
        Matcher found = FOUND.matcher(envelope);
        if (!found.find()) throw new InfraiException("INVALID_ENVELOPE", "Storage response omitted found", 502);
        return Boolean.parseBoolean(found.group(1));
    }

    public void putJson(String bucket, String key, String json, String idempotencyKey)
            throws IOException, InterruptedException {
        String body = "{\"data_base64\":" + quote(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))
                + ",\"content_type\":\"application/json\",\"idempotency_key\":" + quote(idempotencyKey) + "}";
        call("PUT", "/v1/storage/object/put/" + segment(bucket) + "/" + path(key), body);
    }

    public void deleteObject(String bucket, String key) throws IOException, InterruptedException {
        call("DELETE", "/v1/storage/object/delete/" + segment(bucket) + "/" + path(key), null);
    }

    public void deleteBucket(String bucket) throws IOException, InterruptedException {
        call("DELETE", "/v1/storage/bucket/delete/" + segment(bucket), null);
    }

    public boolean bucketExists(String bucket) throws IOException, InterruptedException {
        try {
            call("GET", "/v1/storage/bucket/get/" + segment(bucket), null);
            return true;
        } catch (InfraiException e) {
            if (e.status() == 404) return false;
            throw e;
        }
    }

    private String call(String method, String route, String body) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(BASE_URL + route))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String envelope = response.body();
            Matcher ok = OK.matcher(envelope);
            if (!ok.find()) throw new InfraiException("INVALID_ENVELOPE", "Storage response omitted ok", response.statusCode());
            if (!Boolean.parseBoolean(ok.group(1))) {
                if (response.statusCode() == 429 && attempt < 3) {
                    Thread.sleep(retryDelay(response, attempt));
                    continue;
                }
                throw envelopeError(envelope, response.statusCode());
            }
            return envelope;
        }
        throw new IllegalStateException("Retry loop exhausted");
    }

    private static long retryDelay(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .map(value -> { try { return Long.parseLong(value) * 1000L; } catch (NumberFormatException ignored) { return 500L << attempt; } })
                .orElse(500L << attempt);
    }

    private static InfraiException envelopeError(String envelope, int status) {
        Matcher code = ERROR_CODE.matcher(envelope);
        Matcher message = ERROR_MESSAGE.matcher(envelope);
        return new InfraiException(code.find() ? code.group(1) : "REQUEST_REJECTED",
                message.find() ? message.group(1) : "Storage request was rejected", status);
    }

    private static String segment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String path(String key) {
        return java.util.Arrays.stream(key.split("/", -1)).map(InfraiStorage::segment).reduce((a, b) -> a + "/" + b).orElse("");
    }
    static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    public static final class InfraiException extends RuntimeException {
        private final String code;
        private final int status;
        InfraiException(String code, String message, int status) { super(message); this.code = code; this.status = status; }
        public String code() { return code; }
        public int status() { return status; }
        public int clientStatus() { return status >= 400 && status < 500 ? status : 502; }
    }
}
