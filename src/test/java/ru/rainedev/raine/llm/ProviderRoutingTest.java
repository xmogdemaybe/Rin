package ru.rainedev.raine.llm;

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

/** Провайдер-роутинг Polza.ai / OpenRouter: тело запроса и изоляция резерва. */
class ProviderRoutingTest {

    private static final String MINIMAL = """
            api_id=1
            api_hash=test-hash
            phone=+70000000000
            llm_base_url=http://primary.invalid/v1
            llm_model=primary
            llm_api_key=test-primary-key
            """;

    private static final String SUCCESS = """
            {"data":[{"embedding":[1,2]}],"choices":[{"message":{"role":"assistant","content":"ok"}}]}
            """;

    private static final String ROUTING = """
            llm_provider_only= polza , openrouter ,,
            llm_provider_ignore= bad
            llm_provider_order= openrouter, polza
            llm_provider_sort= PRICE
            llm_provider_allow_fallbacks= TRUE
            llm_provider_max_price_prompt= 1.5
            llm_provider_max_price_completion= 2
            """;

    @TempDir Path dir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> bodies = new ArrayList<>();
    private final Queue<Integer> codes = new ArrayDeque<>();

    private OpenAiCompatibleClient client(String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, MINIMAL + extra);
        Config config = Config.load(file);
        return new OpenAiCompatibleClient(config, request -> {
            bodies.add(mapper.readTree(bodyOf(request)));
            int status = codes.isEmpty() ? 200 : codes.remove();
            return new Reply(status, status == 200 ? SUCCESS : "{\"error\":{\"message\":\"quota\"}}",
                    request, HttpHeaders.of(Map.of(), (a, b) -> true));
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

    @Test
    void emptyProviderSettingsDoNotSendTheKey() throws Exception {
        var client = client("");

        client.chat("system", List.of(Message.user("test")), null);

        assertFalse(bodies.getFirst().has("provider"), bodies.getFirst().toString());
    }

    @Test
    void providerSettingsBecomeACompactRoutingObject() throws Exception {
        var client = client(ROUTING);

        client.chat("system", List.of(Message.user("test")), null);

        JsonNode provider = bodies.getFirst().path("provider");
        assertEquals(mapper.readTree("[\"polza\",\"openrouter\"]"), provider.path("only"));
        assertEquals(mapper.readTree("[\"bad\"]"), provider.path("ignore"));
        assertEquals(mapper.readTree("[\"openrouter\",\"polza\"]"), provider.path("order"));
        assertEquals("price", provider.path("sort").asText());
        assertTrue(provider.path("allow_fallbacks").asBoolean());
        assertEquals(1.5, provider.path("max_price").path("prompt").asDouble(), 0.0001);
        assertEquals(2.0, provider.path("max_price").path("completion").asDouble(), 0.0001);
    }

    @Test
    void onlyTheConfiguredPriceSideIsSent() throws Exception {
        var client = client("llm_provider_max_price_prompt=3\n");

        client.chat("system", List.of(Message.user("test")), null);

        JsonNode maxPrice = bodies.getFirst().path("provider").path("max_price");
        assertEquals(3.0, maxPrice.path("prompt").asDouble(), 0.0001);
        assertFalse(maxPrice.has("completion"), maxPrice.toString());
    }

    @Test
    void invalidProviderSettingsAreRejected() throws Exception {
        for (String line : List.of(
                "llm_provider_sort=random",
                "llm_provider_allow_fallbacks=yes",
                "llm_provider_max_price_prompt=0",
                "llm_provider_max_price_prompt=-1",
                "llm_provider_max_price_prompt=NaN",
                "llm_provider_max_price_completion=abc")) {
            assertThrows(IllegalArgumentException.class, () -> client(line + "\n"), line);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {402, 429})
    void fallbackDropsProviderRouting(int status) throws Exception {
        codes.add(status);
        var client = client(ROUTING + "fallback_model=reserve\n");

        assertEquals("ok", client.chat("system", List.of(Message.user("test")), null).text());

        assertEquals(2, bodies.size(), "основной канал и резерв");
        assertTrue(bodies.get(0).has("provider"), "основной запрос несёт роутинг: " + bodies.get(0));
        assertFalse(bodies.get(1).has("provider"), "резерв не должен получать чужой роутинг: " + bodies.get(1));
        assertEquals("reserve", bodies.get(1).path("model").asText());
    }

    @Test
    void visionHearingAndEmbeddingNeverGetProviderRouting() throws Exception {
        var client = client(ROUTING);

        client.describeImage("vision", "system", "context", new byte[]{1});
        client.hear("audio", "system", "context", new byte[]{1}, "wav");
        client.embedding("memory");

        assertEquals(3, bodies.size());
        assertTrue(bodies.stream().noneMatch(body -> body.has("provider")),
                "роутинг принадлежит только разговорному запросу: " + bodies);
    }

    private record Reply(int statusCode, String body, HttpRequest request,
                         HttpHeaders headers) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private static final class BodySubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();

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
