package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.ErrorNote;
import ru.rainedev.raine.config.Config;

/**
 * Клиент к любому OpenAI-совместимому эндпоинту (routerai, openrouter, ollama).
 * <p>
 * Стриминг сознательно не используется: в агентном цикле важен цельный ответ
 * с вызовами инструментов, а не постепенный вывод текста.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Предел длины ответа. Без него модель изредка застревает в бесконечном
     * рассуждении, и ход упирается в предел времени вместо ответа.
     */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final ObjectMapper mapper = new ObjectMapper();

    /** Предел кэша подписей: процесс живёт неделями, а подписи нужны на ход. */
    private static final int SIGNATURE_CACHE = 256;

    private record Signature(String model, boolean fallback, String value) {}
    private record Response(String text, boolean fallback) {}

    // Одинаковый id может прийти от двух моделей: подпись принадлежит конкретному объекту вызова.
    private final java.util.Map<ToolCall, Signature> thoughtSignatures = new java.util.IdentityHashMap<>();
    private final java.util.Deque<ToolCall> signatureOrder = new java.util.ArrayDeque<>();
    private volatile boolean primaryUsesSignatures;
    private volatile boolean fallbackUsesSignatures;

    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Sender sender;
    private final java.util.function.Consumer<Duration> pause;

    private final Config.Llm config;
    private volatile LlmFailure blocked;

    /**
     * Шлюз однажды отверг «обязан вызвать инструмент» — значит, он его не
     * знает, и платить вторым запросом на каждом ходе незачем.
     */
    private volatile boolean toolChoiceUnsupported;
    private volatile boolean fallbackToolChoiceUnsupported;

    /** Предел модели эмбеддингов — 8192 токена; на кириллице это заметно меньше букв. */
    private static final int MAX_EMBEDDING_CHARS = 8_000;

    private final String embeddingModel;

    public OpenAiCompatibleClient(Config.Llm config, String embeddingModel) {
        this(config, embeddingModel, defaultSender(config.proxy()), OpenAiCompatibleClient::sleep);
    }

    OpenAiCompatibleClient(Config.Llm config, String embeddingModel, Sender sender,
                           java.util.function.Consumer<Duration> pause) {
        this.config = config;
        this.embeddingModel = embeddingModel;
        this.sender = sender;
        this.pause = pause;
        if (config.fallbackModel().isBlank()) {
            log.warn("fallback_model не задана: при квоте резервной разговорной модели нет");
        }
    }

    /** HTTP, при нужде — через прокси: туннель-клиенты дают для него и обычный порт. */
    private static Sender defaultSender(String proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30));
        if (proxy != null && !proxy.isBlank()) {
            builder.proxy(proxySelector(proxy));
        }
        HttpClient http = builder.build();
        return request -> http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Прокси задаётся как host:port. https ходит через него CONNECT-ом,
     * как и положено HTTP-прокси. Скобки IPv6-адреса снимаем.
     */
    private static ProxySelector proxySelector(String proxy) {
        int colon = proxy.lastIndexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("llm_proxy: ожидается host:port, а задано \"" + proxy + '"');
        }
        String host = proxy.substring(0, colon).strip();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String digits = proxy.substring(colon + 1).strip();
        int port;
        try {
            port = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("llm_proxy: порт \"" + digits + "\" — не число");
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            throw new IllegalArgumentException("llm_proxy: \"" + proxy + "\" — нет такого адреса");
        }
        return ProxySelector.of(new InetSocketAddress(host, port));
    }

    @Override
    public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
        return chat(systemPrompt, history, tools, LlmClient.TOOL_CHOICE_AUTO);
    }

    @Override
    public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools, String toolChoice) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.addAll(history);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.model());
        body.put("temperature", config.temperature());
        body.put("max_tokens", MAX_OUTPUT_TOKENS);
        body.set("messages", mapper.valueToTree(messages));
        if (tools != null && tools.isArray() && !tools.isEmpty()) {
            body.set("tools", tools);
            body.put("tool_choice", toolChoice);
        }
        return complete(body, messages);
    }

    /** Отправляет собранное тело и разбирает ответ. */
    private ChatResponse complete(ObjectNode body, List<Message> messages) {
        Response response = post("chat/completions", body, Duration.ofMinutes(5), messages);
        Transcript.save(body, response.text());
        try {
            // иногда вызов приходит текстом, а не в tool_calls: без этого он
            // считался болтовнёй и решение выбрасывалось вместе с ним
            ChatResponse parsed = TextualToolCalls.repair(mapper.readValue(response.text(), ChatResponse.class),
                    body.path("tools").isArray() && !body.path("tools").isEmpty());
            rememberThoughtSignatures(parsed, response.text(), body.path("model").asText(), response.fallback());
            if (parsed.cacheLooksBroken()) {
                log.warn("Посредник не кэширует подсказку — каждый шаг оплачивается как первый");
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать ответ модели", e);
        }
    }

    @Override
    public String describeImage(String model, String systemPrompt, String context, byte[] image) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);

        // картинка идёт отдельной частью сообщения, а не тегом внутри текста:
        // так её видит любой OpenAI-совместимый шлюз. Границы вложения при этом
        // всё равно называются словами: иначе при нескольких картинках непонятно,
        // где кончается одна и начинается другая
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        var parts = userMessage.putArray("content");
        parts.addObject().put("type", "text").put("text", context);
        parts.addObject().put("type", "text").put("text", "<attachments>");
        parts.addObject()
                .put("type", "image_url")
                .putObject("image_url")
                .put("url", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(image));
        parts.addObject().put("type", "text").put("text", "</attachments>");

        String response = post("chat/completions", body, Duration.ofMinutes(3), null).text();
        try {
            return mapper.readValue(response, ChatResponse.class).text();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание картинки", e);
        }
    }

    /** Модели этого ряда слушают звук сами — отдельный распознаватель не нужен. */
    @Override
    public boolean hears() {
        return true;
    }

    @Override
    public String hear(String model, String systemPrompt, String context, byte[] audio, String format) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);

        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);

        // звук идёт отдельной частью сообщения, как картинка: текст подсказки
        // и само вложение эндпоинт путает, если смешать их в одном поле
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        var parts = userMessage.putArray("content");
        parts.addObject().put("type", "text").put("text", context);
        parts.addObject()
                .put("type", "input_audio")
                .putObject("input_audio")
                .put("data", java.util.Base64.getEncoder().encodeToString(audio))
                .put("format", format);

        String response = post("chat/completions", body, Duration.ofMinutes(3), null).text();
        try {
            return mapper.readValue(response, ChatResponse.class).text();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать услышанное", e);
        }
    }

    @Override
    public double[] embedding(String input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", embeddingModel);
        // пустую строку эндпоинт не принимает, а пустой текст встречается
        body.put("input", input == null || input.isBlank() ? " " : trimmed(input));

        String response = post("embeddings", body, Duration.ofMinutes(2), null).text();
        try {
            JsonNode vector = mapper.readTree(response).path("data").path(0).path("embedding");
            if (!vector.isArray()) {
                throw new IllegalStateException("Эндпоинт не вернул вектор: " + response);
            }
            double[] result = new double[vector.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = vector.get(i).asDouble();
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать эмбеддинг", e);
        }
    }

    /**
     * У модели эмбеддингов свой предел длины, и превышение она встречает
     * отказом, а не усечением: вектор не считается, и на той стороне просто
     * ничего не вспоминается. Лучше посчитать вектор по началу текста,
     * чем не посчитать вовсе.
     */
    private static String trimmed(String input) {
        if (input.length() <= MAX_EMBEDDING_CHARS) {
            return input;
        }
        log.debug("Текст для вектора длиннее допустимого, обрезан: {} → {} символов",
                input.length(), MAX_EMBEDDING_CHARS);
        return input.substring(0, MAX_EMBEDDING_CHARS);
    }

    /** Вкладывает сохранённые подписи обратно в tool_calls истории. */
    private synchronized void applyThoughtSignatures(ObjectNode body, List<Message> messages, boolean fallback) {
        for (int m = 0; m < messages.size(); m++) {
            List<ToolCall> calls = messages.get(m).toolCallsOrEmpty();
            for (int c = 0; c < calls.size(); c++) {
                ObjectNode call = (ObjectNode) body.path("messages").path(m).path("tool_calls").path(c);
                call.remove("extra_content");
                Signature signature = thoughtSignatures.get(calls.get(c));
                String value = "";
                if (signature != null && signature.fallback() == fallback
                        && signature.model().equals(body.path("model").asText())) {
                    value = signature.value();
                } else if (fallback ? fallbackUsesSignatures : primaryUsesSignatures) {
                    // Официальный маркер импортированной истории вместо чужой криптографической подписи.
                    value = "skip_thought_signature_validator";
                }
                if (!value.isEmpty()) {
                    call.withObject("/extra_content/google").put("thought_signature", value);
                }
            }
        }
    }

    private synchronized void rememberThoughtSignatures(ChatResponse parsed, String response, String model,
                                                       boolean fallback) throws IOException {
        JsonNode calls = mapper.readTree(response).path("choices").path(0).path("message").path("tool_calls");
        List<ToolCall> parsedCalls = parsed.toolCalls();
        for (int c = 0; c < parsedCalls.size(); c++) {
            String signature = calls.path(c).path("extra_content").path("google").path("thought_signature").asText("");
            if (!signature.isBlank()) {
                if (fallback) fallbackUsesSignatures = true;
                else primaryUsesSignatures = true;
                ToolCall call = parsedCalls.get(c);
                thoughtSignatures.put(call, new Signature(model, fallback, signature));
                signatureOrder.addLast(call);
                if (signatureOrder.size() > SIGNATURE_CACHE) {
                    thoughtSignatures.remove(signatureOrder.removeFirst());
                }
            }
        }
    }

    private Response post(String path, ObjectNode body, Duration timeout, List<Message> messages) {
        LlmFailure unavailable = blocked;
        if (unavailable != null) throw unavailable;
        boolean conversation = messages != null;
        boolean fallback = false;
        boolean retryPrimary = false;
        String toolChoice = body.path("tool_choice").asText("");
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (conversation) {
                if (!toolChoice.isEmpty() && !(fallback ? fallbackToolChoiceUnsupported : toolChoiceUnsupported)) {
                    body.put("tool_choice", toolChoice);
                } else {
                    body.remove("tool_choice");
                }
                applyThoughtSignatures(body, messages, fallback);
            }
            HttpRequest request = request(path, body, timeout, fallback);
            try {
                HttpResponse<String> response = sender.send(request);
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return new Response(response.body(), fallback);
                }
                if (conversation && !fallback && !config.fallbackModel().isBlank()
                        && quotaFailure(status, response.body())) {
                    fallback = true;
                    retryPrimary = status == 429;
                    body.put("model", config.fallbackModel());
                    attempt = 0;
                    log.warn("Квота основной модели исчерпана — пробую резерв в текущем вызове");
                    continue;
                }
                if (conversation && status == 400 && !(fallback ? fallbackUsesSignatures : primaryUsesSignatures)
                        && messages.stream().anyMatch(message -> !message.toolCallsOrEmpty().isEmpty())
                        && missingSignature(response.body())) {
                    if (fallback) fallbackUsesSignatures = true;
                    else primaryUsesSignatures = true;
                    attempt--;
                    continue;
                }
                if (conversation && status == 400 && TOOL_CHOICE_REQUIRED.equals(body.path("tool_choice").asText())) {
                    if (fallback) fallbackToolChoiceUnsupported = true;
                    else toolChoiceUnsupported = true;
                    attempt--;
                    log.warn("Шлюз отверг принудительный вызов инструментов — повторяю без tool_choice");
                    continue;
                }
                String note = ErrorNote.of(response.body().replace(config.apiKey(), "[скрыто]")
                        .replace(config.fallbackApiKey(), "[скрыто]"));
                LlmFailure failure = new LlmFailure(status,
                        "Ошибка эндпоинта (%d): %s".formatted(status, note),
                        !fallback && (conversation || config.fallbackModel().isBlank())
                                && (status == 401 || status == 402 || status == 403));
                if (!failure.retryable()) {
                    if (fallback && retryPrimary) {
                        throw new LlmFailure(status, failure.getMessage(), false, true);
                    }
                    if (failure.requiresOperator()) blocked = failure;
                    throw failure;
                }
                lastFailure = failure;
                if (attempt < MAX_ATTEMPTS) {
                    Duration delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), attempt);
                    log.warn("Эндпоинт {}, повтор через {} с", status, delay.toSeconds());
                    pause.accept(delay);
                    continue;
                }
            } catch (IOException e) {
                lastFailure = new UncheckedIOException("Сеть недоступна", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Запрос прерван", e);
            }

            if (attempt < MAX_ATTEMPTS) {
                log.warn("Попытка {} из {} не удалась: {}", attempt, MAX_ATTEMPTS, lastFailure.getMessage());
                pause.accept(Duration.ofSeconds(2L * attempt));
            }
        }
        throw lastFailure;
    }

    private HttpRequest request(String path, ObjectNode body, Duration timeout, boolean fallback) {
        String url = fallback ? config.fallbackBaseUrl() : config.baseUrl();
        try {
            return HttpRequest.newBuilder(URI.create((url.endsWith("/") ? url : url + "/") + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (fallback ? config.fallbackApiKey() : config.apiKey()))
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать запрос", e);
        }
    }

    private boolean missingSignature(String response) {
        try {
            String message = mapper.readTree(response).path("error").path("message")
                    .asText("").toLowerCase(java.util.Locale.ROOT);
            return message.contains("thought_signature") && (message.contains("missing") || message.contains("required"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean quotaFailure(int status, String response) {
        if (status == 402 || status == 429) return true;
        if (status < 400 || status >= 500 || status == 401) return false;
        try {
            JsonNode error = mapper.readTree(response).path("error");
            for (String field : List.of("code", "type", "status")) {
                String code = error.path(field).asText("").toLowerCase(java.util.Locale.ROOT);
                if (java.util.Set.of("insufficient_quota", "quota_exceeded", "resource_exhausted",
                        "rate_limit_exceeded", "insufficient_credits", "insufficient_balance").contains(code)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    static Duration retryDelay(String header, int attempt) {
        long seconds = 2L * attempt;
        try { seconds = Math.max(seconds, Long.parseLong(header.strip())); }
        catch (NumberFormatException ignored) {
            try {
                var date = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                seconds = Math.max(seconds, Duration.between(java.time.Instant.now(), date.toInstant()).toSeconds());
            } catch (java.time.format.DateTimeParseException invalid) { /* use backoff */ }
        }
        return Duration.ofSeconds(Math.clamp(seconds, 1, 3600));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", e);
        }
    }
}
