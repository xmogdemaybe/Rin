package ru.rainedev.raine.llm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.rainedev.raine.config.Config;

class EndpointRoutingTest {

    private static final String MINIMAL = """
            api_id=1
            api_hash=test-hash
            phone=+70000000000
            llm_base_url=http://primary.invalid/v1
            llm_model=primary
            llm_api_key=test-primary-key
            embedding_model=embedding-test
            """;

    private static final String SUCCESS = """
            {"data":[{"embedding":[1,2]}],"choices":[{"message":{"role":"assistant","content":"ok"}}]}
            """;

    @TempDir Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private final Queue<Integer> codes = new ArrayDeque<>();
    private String error = "{\"error\":{\"message\":\"endpoint error\"}}";

    private OpenAiCompatibleClient client(String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, MINIMAL + extra);
        Config config = Config.load(file);
        return new OpenAiCompatibleClient(config, request -> {
            requests.add(request);
            bodies.add(mapper.readTree(bodyOf(request)));
            int status = codes.isEmpty() ? 200 : codes.remove();
            return new Reply(status, status == 200 ? SUCCESS : error, request, HttpHeaders.of(Map.of(), (a, b) -> true));
        }, pause -> { });
    }

    private static String bodyOf(HttpRequest request) {
        try {
            var subscriber = new BodySubscriber();
            request.bodyPublisher().orElseThrow().subscribe(subscriber);
            return subscriber.body();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> models() {
        return bodies.stream().map(body -> body.path("model").asText()).toList();
    }

    @Test
    void embeddingAndVisionUseTheirOwnAddressesAndKeys() throws Exception {
        var client = client("""
                embedding_base_url=http://embedding.invalid/v1
                embedding_api_key=test-embedding-key
                vision_base_url=http://vision.invalid/v1
                vision_api_key=test-vision-key
                """);

        client.embedding("memory");
        client.describeImage("vision", "system", "context", new byte[]{1});

        assertEquals("embedding.invalid", requests.get(0).uri().getHost());
        assertEquals("/v1/embeddings", requests.get(0).uri().getPath());
        assertEquals("Bearer test-embedding-key",
                requests.get(0).headers().firstValue("Authorization").orElseThrow());
        assertEquals("vision.invalid", requests.get(1).uri().getHost());
        assertEquals("/v1/chat/completions", requests.get(1).uri().getPath());
        assertEquals("Bearer test-vision-key",
                requests.get(1).headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void imageDataUrlUsesMagicBytes() throws Exception {
        var client = client("");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1};
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 1};
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};

        client.describeImage("vision", "system", "context", png);
        client.describeImage("vision", "system", "context", webp);
        client.describeImage("vision", "system", "context", jpeg);

        for (int i = 0; i < bodies.size(); i++) {
            String url = bodies.get(i).path("messages").get(1).path("content").get(2)
                    .path("image_url").path("url").asText();
            byte[] expected = i == 0 ? png : i == 1 ? webp : jpeg;
            String mime = i == 0 ? "image/png" : i == 1 ? "image/webp" : "image/jpeg";
            assertTrue(url.startsWith("data:" + mime + ";base64,"), url);
            assertArrayEquals(expected, Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)),
                    "байты картинки не перекодируются");
        }
    }

    @Test
    void blankAddressesInheritTheMainEndpoint() throws Exception {
        var client = client("""
                embedding_base_url=
                embedding_api_key=
                vision_base_url=
                vision_api_key=
                """);

        client.embedding("memory");
        client.describeImage("vision", "system", "context", new byte[]{1});

        assertTrue(requests.stream().allMatch(request -> request.uri().getHost().equals("primary.invalid")));
        assertTrue(requests.stream().allMatch(request -> request.headers().firstValue("Authorization")
                .orElseThrow().equals("Bearer test-primary-key")));
    }

    @Test
    void chatAndHearingStayOnTheMainEndpoint() throws Exception {
        var client = client("""
                embedding_base_url=http://embedding.invalid/v1
                embedding_api_key=test-embedding-key
                vision_base_url=http://vision.invalid/v1
                vision_api_key=test-vision-key
                """);

        client.chat("system", List.of(Message.user("test")), null);
        client.hear("audio", "system", "context", new byte[]{1}, "wav");

        assertTrue(requests.stream().allMatch(request -> request.uri().getHost().equals("primary.invalid")));
        assertTrue(requests.stream().allMatch(request -> request.headers().firstValue("Authorization")
                .orElseThrow().equals("Bearer test-primary-key")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402})
    void embeddingFailureBlocksOnlyEmbedding(int status) throws Exception {
        codes.add(status);
        var client = client("fallback_model=reserve\nfallback_base_url=http://reserve.invalid/v1\n");

        assertThrows(LlmFailure.class, () -> client.embedding("memory"));
        int sent = requests.size();
        assertThrows(LlmFailure.class, () -> client.embedding("memory"));
        assertEquals(sent, requests.size(), "повтор заблокированного сервиса не ходит в сеть");

        codes.addAll(List.of(429, 200));
        assertEquals("ok", client.chat("test", List.of(), null).text());
        assertEquals(List.of("embedding-test", "primary", "reserve"), models(),
                "эмбеддинги не должны включать разговорный резерв");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402})
    void visionFailureBlocksOnlyVision(int status) throws Exception {
        codes.add(status);
        var client = client("fallback_model=reserve\nfallback_base_url=http://reserve.invalid/v1\n");

        assertThrows(LlmFailure.class,
                () -> client.describeImage("vision", "system", "context", new byte[]{1}));
        int sent = requests.size();
        assertThrows(LlmFailure.class,
                () -> client.describeImage("vision", "system", "context", new byte[]{1}));
        assertEquals(sent, requests.size(), "повтор заблокированного зрения не ходит в сеть");

        codes.addAll(List.of(429, 200));
        assertEquals("ok", client.chat("test", List.of(), null).text());
        assertEquals(List.of("vision", "primary", "reserve"), models(),
                "зрение не должно включать разговорный резерв");
    }

    @Test
    void chatPaymentFailureDoesNotBlockSeparateServices() throws Exception {
        var client = client("""
                embedding_base_url=http://embedding.invalid/v1
                embedding_api_key=test-embedding-key
                vision_base_url=http://vision.invalid/v1
                vision_api_key=test-vision-key
                """);
        codes.add(402);

        assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        client.embedding("memory");
        client.describeImage("vision", "system", "context", new byte[]{1});

        assertEquals(3, requests.size(), "отказ чата не должен закрывать чужие адреса");
        assertEquals("embedding.invalid", requests.get(1).uri().getHost());
        assertEquals("vision.invalid", requests.get(2).uri().getHost());
    }

    @Test
    void failureMasksEveryConfiguredKey() throws Exception {
        codes.add(400);
        error = "leak primary-key fallback-key embedding-key vision-key";
        var client = client("""
                llm_api_key=primary-key
                fallback_model=reserve
                fallback_api_key=fallback-key
                embedding_api_key=embedding-key
                vision_api_key=vision-key
                """);

        var failure = assertThrows(LlmFailure.class, () -> client.embedding("memory"));

        for (String key : List.of("primary-key", "fallback-key", "embedding-key", "vision-key")) {
            assertFalse(failure.getMessage().contains(key), failure.getMessage());
        }
        assertTrue(failure.getMessage().contains("[скрыто]"), failure.getMessage());
    }

    private record Reply(int statusCode, String body, HttpRequest request,
                         HttpHeaders headers) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    /** Собирает тело запроса тем же путём, каким его отправляет настоящий HttpClient. */
    private static final class BodySubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer buffer) {
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }

        @Override
        public void onError(Throwable error) { result.completeExceptionally(error); }

        @Override
        public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }

        String body() {
            return result.join();
        }
    }
}
