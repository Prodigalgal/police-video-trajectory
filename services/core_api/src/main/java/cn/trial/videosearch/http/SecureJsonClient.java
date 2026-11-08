package cn.trial.videosearch.http;

import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Small HTTPS client with a dedicated CA and bounded responses; no redirects. */
public final class SecureJsonClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final URI base;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    public SecureJsonClient(String baseUrl, String caPath) {
        try {
            base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
            if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null ||
                    base.getQuery() != null || base.getFragment() != null || caPath == null || caPath.isBlank()) {
                throw new IllegalArgumentException("invalid TLS configuration");
            }
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            try (InputStream in = Files.newInputStream(Path.of(caPath))) {
                var certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
                if (certs.isEmpty()) throw new IllegalArgumentException("empty CA file");
                int index = 0;
                for (Certificate cert : certs) store.setCertificateEntry("ca-" + index++, cert);
            }
            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, tmf.getTrustManagers(), null);
            client = HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(10)).build();
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid TLS configuration", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Map<String, ?> body, String authorization, Duration timeout) {
        var result = exchange("POST", path, body, authorization, timeout);
        if (result.status() < 200 || result.status() >= 300) throw new UpstreamFailure(result.status());
        return result.body();
    }

    public void delete(String path,Map<String,?> body,String authorization,Duration timeout) {
        var result=exchange("DELETE",path,body,authorization,timeout);
        if(result.status()<200 || result.status()>=300) throw new UpstreamFailure(result.status());
    }

    /** A version conflict on external_gte means a newer event is already indexed. */
    public void putVersioned(String index, UUID observationId, long version, Map<String, ?> document, String authorization) {
        if (observationId == null || version < 1) throw new IllegalArgumentException("invalid index event");
        String path = "/" + validateIndex(index) + "/_doc/" + observationId + "?version=" + version + "&version_type=external_gte";
        var result = exchange("PUT", path, document, authorization, Duration.ofSeconds(30));
        if (result.status() >= 200 && result.status() < 300) return;
        if (result.status() == 409 && result.body().get("error") instanceof Map<?, ?> error &&
                "version_conflict_engine_exception".equals(error.get("type"))) return;
        throw new UpstreamFailure();
    }

    public static String validateIndex(String index) {
        if (index == null || !index.matches("police-video-observations(?:-[a-z0-9-]{1,40})?")) throw new IllegalArgumentException("invalid index name");
        return index;
    }

    private record Result(int status, Map<String, Object> body) {}

    @SuppressWarnings("unchecked")
    private Result exchange(String method, String path, Map<String, ?> body, String authorization, Duration timeout) {
        if (!path.startsWith("/") || authorization == null || authorization.isBlank()) throw new IllegalArgumentException("invalid request");
        try {
            var request = HttpRequest.newBuilder(URI.create(base.toString() + path)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Authorization", authorization)
                    .method(method, body==null ? HttpRequest.BodyPublishers.noBody() :
                            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            var result = client.send(request, info -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES));
            Object parsed = json.readValue(result.body(), Object.class);
            if (!(parsed instanceof Map<?, ?>)) throw new UpstreamFailure();
            return new Result(result.statusCode(), (Map<String, Object>) parsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamFailure();
        } catch (Exception e) {
            throw new UpstreamFailure();
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private Flow.Subscription subscription;
        private long received;
        private boolean terminated;

        LimitedBodySubscriber(long limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; delegate.onSubscribe(s); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            if (terminated) return;
            for (ByteBuffer chunk : chunks) received += chunk.remaining();
            if (received > limit) {
                terminated = true;
                subscription.cancel();
                delegate.onError(new IllegalStateException("response too large"));
            } else {
                delegate.onNext(chunks);
            }
        }
        @Override public void onError(Throwable t) { if (!terminated) { terminated = true; delegate.onError(t); } }
        @Override public void onComplete() { if (!terminated) { terminated = true; delegate.onComplete(); } }
    }

    public static final class UpstreamFailure extends RuntimeException {
        private final int statusCode;
        public UpstreamFailure() { this(0); }
        public UpstreamFailure(int statusCode) { super("upstream unavailable or invalid");this.statusCode=statusCode; }
        public int statusCode() { return statusCode; }
    }
}
