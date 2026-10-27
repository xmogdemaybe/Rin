package ru.rainedev.raine.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.rainedev.raine.config.Config;

class QuotaFallbackTest {
    @TempDir Path dir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<Duration> pauses = new ArrayList<>();
    private final Queue<Integer> codes = new ArrayDeque<>();
    private String error = "{\"error\":{\"message\":\"endpoint error\"}}";
    private String success = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";

    private Config config(String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, """
                api_id=1
                api_hash=test-hash
                phone=+70000000000
                llm_base_url=http://primary.invalid/v1
                llm_model=primary
                llm_api_key=test-primary-key
                """ + extra);
        return Config.load(file);
    }

    private OpenAiCompatibleClient client(String extra) throws IOException {
        Transcript.directory(dir.resolve("transcript"));
        return new OpenAiCompatibleClient(config(extra).llm(), "embedding-test", request -> {
            requests.add(request);
            bodies.add(mapper.readTree(bodyOf(request)));
            int status = codes.isEmpty() ? 200 : codes.remove();
            return new Reply(status, status == 200 ? success : error, request);
        }, pauses::add);
    }

    @Test
    void quotaSwitchesModelEndpointAndKeyThenStartsPrimaryAgain() throws Exception {
        codes.addAll(List.of(429, 200, 200));
        var client = client("""
                fallback_model=reserve
                fallback_base_url=http://reserve.invalid/api/
                fallback_api_key=test-reserve-key
                """);
        var history = List.of(Message.user("test"));
        assertEquals("ok", client.chat("system", history, null).text());
        assertEquals("ok", client.chat("system", history, null).text());
        assertEquals(List.of("primary", "reserve", "primary"), models());
        assertEquals("http://reserve.invalid/api/chat/completions", requests.get(1).uri().toString());
        assertEquals("Bearer test-reserve-key", requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertEquals(bodies.get(0).path("messages"), bodies.get(1).path("messages"));
        assertTrue(pauses.isEmpty());
        assertEquals("test", history.getFirst().content());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fallback_base_url=\nfallback_api_key=\n"})
    void missingOrBlankEndpointAndKeyAreInherited(String extra) throws Exception {
        codes.addAll(List.of(429, 200));
        client("fallback_model=reserve\n" + extra).chat("test", List.of(), null);
        assertEquals(List.of("primary", "reserve"), models());
        assertEquals(requests.getFirst().uri(), requests.getLast().uri());
        assertEquals(requests.getFirst().headers(), requests.getLast().headers());
        assertFalse(Files.readString(dir.resolve("config.properties")).contains("fallback_api_key=test-primary-key"));
    }

    @ParameterizedTest
    @ValueSource(ints = {402, 429})
    void quotaWithoutReserveKeepsOldPolicy(int status) throws Exception {
        codes.addAll(List.of(status, status, status));
        var client = client("");
        var failure = assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        assertEquals(status, failure.status());
        assertEquals(status == 402 ? 1 : 3, requests.size());
        assertTrue(models().stream().allMatch("primary"::equals));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403})
    void structuredQuotaCodeIsReadBeforeErrorTruncation(int status) throws Exception {
        codes.addAll(List.of(status, 200));
        error = "{\"error\":{\"message\":\"" + "detail ".repeat(80)
                + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
        client("fallback_model=reserve\n").chat("test", List.of(), null);
        assertEquals(List.of("primary", "reserve"), models());
    }

    @Test
    void paymentRequiredUsesReserve() throws Exception {
        codes.addAll(List.of(402, 200));
        client("fallback_model=reserve\n").chat("test", List.of(), null);
        assertEquals(List.of("primary", "reserve"), models());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 503})
    void unrelatedErrorsDoNotSelectReserve(int status) throws Exception {
        codes.addAll(List.of(status, status, status));
        error = "{\"error\":{\"message\":\"Invalid request; see quota documentation\",\"code\":\"invalid_request\"}}";
        var client = client("fallback_model=reserve\n");
        assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        assertEquals(status == 503 ? 3 : 1, requests.size());
        assertTrue(models().stream().allMatch("primary"::equals));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 429})
    void reserveFailureDoesNotBlockPrimaryOnNextCall(int reserveStatus) throws Exception {
        codes.addAll(List.of(429, reserveStatus, reserveStatus, reserveStatus));
        var client = client("fallback_model=reserve\n");
        var failure = assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        assertFalse(failure.requiresOperator());
        assertTrue(requests.size() <= 4);
        codes.clear();
        assertEquals("ok", client.chat("test", List.of(), null).text());
        assertEquals("primary", models().getLast());
    }

    @Test
    void embeddingsNeverUseConversationReserve() throws Exception {
        codes.addAll(List.of(429, 429, 429));
        var client = client("fallback_model=reserve\n");
        assertThrows(LlmFailure.class, () -> client.embedding("memory"));
        assertEquals(List.of("embedding-test", "embedding-test", "embedding-test"), models());
        assertTrue(requests.stream().allMatch(r -> r.uri().getPath().equals("/v1/embeddings")));
    }

    @Test
    void sameModelAtAnotherEndpointIsAValidReserve() throws Exception {
        codes.addAll(List.of(429, 200));
        client("fallback_model=primary\nfallback_base_url=http://reserve.invalid/v1\n").chat("test", List.of(), null);
        assertEquals("reserve.invalid", requests.getLast().uri().getHost());
    }

    @Test
    void reserveToolChoiceRejectionDoesNotRetryPrimaryOrDisableItsTools() throws Exception {
        codes.addAll(List.of(429, 400, 200, 200));
        var client = client("fallback_model=reserve\n");
        var tools = mapper.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"wait\"}}]");
        client.chat("test", List.of(), tools, LlmClient.TOOL_CHOICE_REQUIRED);
        client.chat("test", List.of(), tools, LlmClient.TOOL_CHOICE_REQUIRED);
        assertEquals(List.of("primary", "reserve", "reserve", "primary"), models());
        assertFalse(bodies.get(2).has("tool_choice"));
        assertEquals("required", bodies.get(3).path("tool_choice").asText());
        assertEquals(tools, bodies.get(2).path("tools"));
    }

    @Test
    void primaryToolChoiceRejectionDoesNotDisableReserveTools() throws Exception {
        codes.addAll(List.of(400, 429, 200));
        var client = client("fallback_model=reserve\n");
        var tools = mapper.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"wait\"}}]");
        client.chat("test", List.of(), tools, LlmClient.TOOL_CHOICE_REQUIRED);
        assertEquals(List.of("primary", "primary", "reserve"), models());
        assertFalse(bodies.get(1).has("tool_choice"));
        assertEquals("required", bodies.get(2).path("tool_choice").asText());
    }

    @Test
    void thoughtSignaturesStayWithTheirOriginalModel() throws Exception {
        success = """
                {"choices":[{"message":{"role":"assistant","tool_calls":[{
                "id":"call-1","type":"function","function":{"name":"wait","arguments":"{}"},
                "extra_content":{"google":{"thought_signature":"primary-signature"}}}]}}]}
                """;
        var client = client("fallback_model=reserve\n");
        var response = client.chat("test", List.of(), null);
        codes.addAll(List.of(429, 200, 200));
        var history = List.of(response.firstMessage().orElseThrow(), Message.toolResult("call-1", "done"));
        client.chat("test", history, null);
        client.chat("test", history, null);
        assertEquals("primary-signature", bodies.get(1).at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText());
        assertTrue(bodies.get(2).at("/messages/1/tool_calls/0/extra_content").isMissingNode());
        assertEquals("primary-signature", bodies.get(3).at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText());
    }

    @Test
    void malformedQuotaResponseRemainsAnHttpFailure() throws Exception {
        codes.add(403);
        error = "";
        var client = client("fallback_model=reserve\n");
        assertEquals(403, assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null)).status());
    }

    @Test
    void endpointCannotEchoEitherConfiguredKeyInFailure() throws Exception {
        codes.addAll(List.of(429, 400));
        error = "bad credentials test-primary-key test-reserve-key";
        var client = client("fallback_model=reserve\nfallback_api_key=test-reserve-key\n");
        var failure = assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        assertFalse(failure.getMessage().contains("test-primary-key"));
        assertFalse(failure.getMessage().contains("test-reserve-key"));
    }

    @Test
    void explicitVisionAndAudioModelsDoNotUseTextReserve() throws Exception {
        var client = client("fallback_model=reserve\n");
        codes.addAll(List.of(429, 429, 429));
        assertThrows(LlmFailure.class, () -> client.describeImage("vision", "test", "test", new byte[]{1}));
        codes.addAll(List.of(429, 429, 429));
        assertThrows(LlmFailure.class, () -> client.hear("audio", "test", "test", new byte[]{1}, "wav"));
        assertEquals(List.of("vision", "vision", "vision", "audio", "audio", "audio"), models());
    }

    @Test
    void absentReserveWarnsWithoutLoggingConfiguration() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            client("");
            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("fallback_model")));
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("test-primary-key")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void interruptionDoesNotInvokeReserveAndPreservesInterruptFlag() throws Exception {
        var client = new OpenAiCompatibleClient(config("fallback_model=reserve\n").llm(), "embedding-test", request -> {
            requests.add(request);
            throw new InterruptedException("test interruption");
        }, pauses::add);
        try {
            assertThrows(IllegalStateException.class, () -> client.chat("test", List.of(), null));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, requests.size());
            assertTrue(pauses.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reserveRetriesRespectItsRetryAfter() throws Exception {
        codes.addAll(List.of(429, 429, 200));
        client("fallback_model=reserve\n").chat("test", List.of(), null);
        assertEquals(List.of("primary", "reserve", "reserve"), models());
        assertEquals(List.of(Duration.ofSeconds(120)), pauses);
    }

    @Test
    void paymentFailureInEmbeddingsDoesNotDisableConversationReserve() throws Exception {
        codes.addAll(List.of(402, 429, 200));
        var client = client("fallback_model=reserve\n");
        assertThrows(LlmFailure.class, () -> client.embedding("memory"));
        assertEquals("ok", client.chat("test", List.of(), null).text());
        assertEquals(List.of("embedding-test", "primary", "reserve"), models());
    }

    @Test
    void permanentReserveFailurePreservesRetryOfTemporaryPrimaryQuota() throws Exception {
        codes.addAll(List.of(429, 401));
        var client = client("fallback_model=reserve\n");
        var failure = assertThrows(LlmFailure.class, () -> client.chat("test", List.of(), null));
        assertTrue(failure.retryable());
        assertFalse(failure.requiresOperator());
        assertEquals(2, requests.size());
    }

    @Test
    void identicalToolIdsFromDifferentModelsDoNotMixSignatures() throws Exception {
        success = signedTool("primary-signature");
        var client = client("fallback_model=reserve\n");
        var primary = client.chat("test", List.of(), null).firstMessage().orElseThrow();
        success = signedTool("reserve-signature");
        codes.addAll(List.of(429, 200));
        var reserve = client.chat("test", List.of(), null).firstMessage().orElseThrow();
        var history = List.of(primary, Message.toolResult("call-1", "done"), reserve, Message.toolResult("call-1", "done"));
        codes.addAll(List.of(429, 200));
        client.chat("test", history, null);
        assertEquals("primary-signature", bodies.get(3).at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText());
        assertEquals("skip_thought_signature_validator", bodies.get(3).at("/messages/3/tool_calls/0/extra_content/google/thought_signature").asText());
        assertEquals("reserve-signature", bodies.get(4).at("/messages/3/tool_calls/0/extra_content/google/thought_signature").asText());
        assertEquals("skip_thought_signature_validator", bodies.get(4).at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText());
    }

    @Test
    void primaryCanImportReserveHistoryBeforeItHasEverReturnedASignature() throws Exception {
        codes.addAll(List.of(429, 200));
        success = signedTool("reserve-signature");
        var client = client("fallback_model=reserve\n");
        var reserve = client.chat("test", List.of(), null).firstMessage().orElseThrow();
        codes.addAll(List.of(400, 200));
        error = "{\"error\":{\"message\":\"Function call is missing a thought_signature\"}}";
        client.chat("test", List.of(reserve, Message.toolResult("call-1", "done")), null);
        assertEquals(List.of("primary", "reserve", "primary", "primary"), models());
        assertTrue(bodies.get(2).at("/messages/1/tool_calls/0/extra_content").isMissingNode());
        assertEquals("skip_thought_signature_validator", bodies.get(3).at("/messages/1/tool_calls/0/extra_content/google/thought_signature").asText());
    }

    private static String signedTool(String signature) {
        return """
                {"choices":[{"message":{"role":"assistant","tool_calls":[{
                "id":"call-1","type":"function","function":{"name":"wait","arguments":"{}"},
                "extra_content":{"google":{"thought_signature":"%s"}}}]}}]}
                """.formatted(signature);
    }

    private List<String> models() {
        return bodies.stream().map(b -> b.path("model").asText()).toList();
    }

    private static String bodyOf(HttpRequest request) {
        var result = new CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(StandardCharsets.UTF_8)); }
        });
        return result.join();
    }

    private record Reply(int statusCode, String body, HttpRequest request) implements HttpResponse<String> {
        public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of("Retry-After", List.of("120")), (a, b) -> true); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
