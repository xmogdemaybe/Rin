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

    /**
     * Признак разговора. Посредник держит по нему кэш подсказки: при двух
     * десятках шагов на одно уведомление разница видна прямо в счёте.
     */
    private final String sessionId = java.util.UUID.randomUUID().toString();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Подписи размышлений Gemini 3 (BUG-04): модель отдаёт их в
     * extra_content.google.thought_signature каждого вызова инструмента и
     * требует обратно в следующем запросе. Обычные record-классы это поле
     * молча роняют, поэтому храним по id вызова и втыкаем в историю сам.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> thoughtSignatures =
            new java.util.concurrent.ConcurrentHashMap<>();
    @FunctionalInterface
    interface Sender {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    private final Sender sender;
    private final java.util.function.Consumer<Duration> pause;

    private final Config.Llm config;
    private volatile LlmFailure blocked;
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

        applyThoughtSignatures(body);
        try {
            return complete(body);
        } catch (LlmFailure failure) {
            if (failure.status() != 400 || !LlmClient.TOOL_CHOICE_REQUIRED.equals(toolChoice)) {
                throw failure;
            }
            // «обязан вызвать инструмент» шлюз отверг — тот же запрос, но без запрета текста
            log.warn("Шлюз отверг принудительный вызов инструментов, повторяю как обычно");
            body.remove("tool_choice");
            return complete(body);
        }
    }

    /** Отправляет собранное тело и разбирает ответ. */
    private ChatResponse complete(ObjectNode body) {
        String response = post("chat/completions", body, Duration.ofMinutes(5));
        Transcript.save(body, response);
        try {
            ChatResponse parsed = mapper.readValue(response, ChatResponse.class);
            rememberThoughtSignatures(response);
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

        String response = post("chat/completions", body, Duration.ofMinutes(3));
        try {
            return mapper.readValue(response, ChatResponse.class).text();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось разобрать описание картинки", e);
        }
    }

    @Override
    public double[] embedding(String input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", embeddingModel);
        // пустую строку эндпоинт не принимает, а пустой текст встречается
        body.put("input", input == null || input.isBlank() ? " " : trimmed(input));

        String response = post("embeddings", body, Duration.ofMinutes(2));
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
    private void applyThoughtSignatures(ObjectNode body) {
        for (JsonNode message : body.path("messages")) {
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray()) continue;
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText("");
                String signature = id.isBlank() ? null : thoughtSignatures.get(id);
                if (signature != null) {
                    ((ObjectNode) call).withObject("/extra_content/google")
                            .put("thought_signature", signature);
                }
            }
        }
    }

    /** Запоминает подписи размышлений из ответа модели по id вызова. */
    private void rememberThoughtSignatures(String response) throws IOException {
        JsonNode calls = mapper.readTree(response)
                .path("choices").path(0).path("message").path("tool_calls");
        if (!calls.isArray()) return;
        for (JsonNode call : calls) {
            String id = call.path("id").asText("");
            String signature = call.path("extra_content").path("google").path("thought_signature").asText("");
            if (!id.isBlank() && !signature.isBlank()) {
                thoughtSignatures.putIfAbsent(id, signature);
            }
        }
    }

    private String post(String path, ObjectNode body, Duration timeout) {
        LlmFailure unavailable = blocked;
        if (unavailable != null) throw unavailable;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать запрос", e);
        }

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = sender.send(request);
                if (response.statusCode() / 100 == 2) {
                    return response.body();
                }
                LlmFailure failure = new LlmFailure(response.statusCode(),
                        "Ошибка эндпоинта (%d): %s".formatted(response.statusCode(), response.body()));
                if (!failure.retryable()) {
                    if (failure.requiresOperator()) blocked = failure;
                    throw failure;
                }
                lastFailure = failure;
                if (attempt < MAX_ATTEMPTS) {
                    Duration delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), attempt);
                    log.warn("Эндпоинт {}, повтор через {} с", response.statusCode(), delay.toSeconds());
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

    private String baseUrl() {
        String url = config.baseUrl();
        return url.endsWith("/") ? url : url + "/";
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
