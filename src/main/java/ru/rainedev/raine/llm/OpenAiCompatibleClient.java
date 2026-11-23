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

    /**
     * Сколько всего попыток на одну ошибку. На 429 их меньше: повтор ровно один,
     * потому что спешка продлевает лимит, а не обходит его.
     */
    private static final int MAX_ATTEMPTS = 3;

    /** Google просит подождать в RetryInfo: {"retryDelay":"17s"}. */
    private static final java.util.regex.Pattern GOOGLE_DELAY =
            java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)s");

    /** OpenAI пишет срок в текст ошибки: «Please try again in 20ms». */
    private static final java.util.regex.Pattern TRY_AGAIN =
            java.util.regex.Pattern.compile("(?i)try again in\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)\\b");

    /** Дольше часа ждать незачем: уведомление и так устареет. */
    private static final long MAX_RETRY_SECONDS = 3600;

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
    private final String visionBaseUrl;
    private final String visionApiKey;
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private volatile LlmFailure blocked;
    private volatile LlmFailure visionBlocked;
    private volatile LlmFailure embeddingBlocked;

    /** Назначение запроса: у зрения и эмбеддингов свои адрес, ключ и запрет повторов. */
    private enum Purpose { CHAT, VISION, EMBEDDING }

    /**
     * Шлюз однажды отверг «обязан вызвать инструмент» — значит, он его не
     * знает, и платить вторым запросом на каждом ходе незачем.
     */
    private volatile boolean toolChoiceUnsupported;
    private volatile boolean fallbackToolChoiceUnsupported;

    /** Предел модели эмбеддингов — 8192 токена; на кириллице это заметно меньше букв. */
    private static final int MAX_EMBEDDING_CHARS = 8_000;

    private final String embeddingModel;

    public OpenAiCompatibleClient(Config config) {
        this(config, defaultSender(config.llm().proxy()), OpenAiCompatibleClient::sleep);
    }

    OpenAiCompatibleClient(Config config, Sender sender, java.util.function.Consumer<Duration> pause) {
        this(config.llm(), config.embeddingModel(), config.vision().baseUrl(), config.vision().apiKey(),
                config.embeddingBaseUrl(), config.embeddingApiKey(), sender, pause);
    }

    public OpenAiCompatibleClient(Config.Llm config, String embeddingModel) {
        this(config, embeddingModel, config.baseUrl(), config.apiKey(), config.baseUrl(), config.apiKey(),
                defaultSender(config.proxy()), OpenAiCompatibleClient::sleep);
    }

    OpenAiCompatibleClient(Config.Llm config, String embeddingModel, Sender sender,
                           java.util.function.Consumer<Duration> pause) {
        this(config, embeddingModel, config.baseUrl(), config.apiKey(), config.baseUrl(), config.apiKey(),
                sender, pause);
    }

    private OpenAiCompatibleClient(Config.Llm config, String embeddingModel,
                                   String visionBaseUrl, String visionApiKey,
                                   String embeddingBaseUrl, String embeddingApiKey,
                                   Sender sender, java.util.function.Consumer<Duration> pause) {
        this.config = config;
        this.embeddingModel = embeddingModel;
        this.visionBaseUrl = visionBaseUrl;
        this.visionApiKey = visionApiKey;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingApiKey = embeddingApiKey;
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
        ObjectNode provider = providerBody();
        if (provider != null) {
            body.set("provider", provider);
        }
        return complete(body, messages);
    }

    /**
     * Провайдер-роутинг для Polza.ai и OpenRouter. Пустые настройки не шлют
     * ничего: ключ {@code provider} появляется только тогда, когда есть что
     * сказать, иначе посредник получает незнакомое поле на ровном месте.
     */
    private ObjectNode providerBody() {
        ObjectNode provider = mapper.createObjectNode();
        boolean any = false;
        if (!config.providerOnly().isEmpty()) {
            provider.set("only", mapper.valueToTree(config.providerOnly()));
            any = true;
        }
        if (!config.providerIgnore().isEmpty()) {
            provider.set("ignore", mapper.valueToTree(config.providerIgnore()));
            any = true;
        }
        if (!config.providerOrder().isEmpty()) {
            provider.set("order", mapper.valueToTree(config.providerOrder()));
            any = true;
        }
        if (!config.providerSort().isBlank()) {
            provider.put("sort", config.providerSort());
            any = true;
        }
        if (config.providerAllowFallbacks() != null) {
            provider.put("allow_fallbacks", config.providerAllowFallbacks());
            any = true;
        }
        if (config.providerMaxPricePrompt() != null || config.providerMaxPriceCompletion() != null) {
            ObjectNode maxPrice = provider.putObject("max_price");
            if (config.providerMaxPricePrompt() != null) {
                maxPrice.put("prompt", config.providerMaxPricePrompt());
            }
            if (config.providerMaxPriceCompletion() != null) {
                maxPrice.put("completion", config.providerMaxPriceCompletion());
            }
            any = true;
        }
        return any ? provider : null;
    }

    /** Отправляет собранное тело и разбирает ответ. */
    private ChatResponse complete(ObjectNode body, List<Message> messages) {
        Response response = post(Purpose.CHAT, "chat/completions", body, Duration.ofMinutes(5), messages);
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

        String response = post(Purpose.VISION, "chat/completions", body, Duration.ofMinutes(3), null).text();
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

        String response = post(Purpose.CHAT, "chat/completions", body, Duration.ofMinutes(3), null).text();
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

        String response = post(Purpose.EMBEDDING, "embeddings", body, Duration.ofMinutes(2), null).text();
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
        JsonNode message = mapper.readTree(response).path("choices").path(0).path("message");
        JsonNode calls = message.path("tool_calls");
        // у вызова, написанного текстом, своей записи в tool_calls нет: тогда
        // модель помечает подписью сообщение целиком
        String messageSignature = message.path("extra_content").path("google").path("thought_signature").asText("");
        List<ToolCall> parsedCalls = parsed.toolCalls();
        for (int c = 0; c < parsedCalls.size(); c++) {
            String signature = calls.path(c).path("extra_content").path("google").path("thought_signature").asText("");
            if (signature.isBlank()) {
                signature = messageSignature;
            }
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

    private Response post(Purpose purpose, String path, ObjectNode body, Duration timeout, List<Message> messages) {
        LlmFailure unavailable = blockedFor(purpose);
        if (unavailable != null) throw unavailable;
        boolean conversation = messages != null;
        boolean fallback = false;
        boolean retryPrimary = false;
        String toolChoice = body.path("tool_choice").asText("");
        RuntimeException lastFailure = null;
        int rateLimited = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (conversation) {
                if (!toolChoice.isEmpty() && !(fallback ? fallbackToolChoiceUnsupported : toolChoiceUnsupported)) {
                    body.put("tool_choice", toolChoice);
                } else {
                    body.remove("tool_choice");
                }
                applyThoughtSignatures(body, messages, fallback);
            }
            if (fallback) {
                // резервный канал может быть совсем другим провайдером: чужие
                // правила роутинга ему не указ
                body.remove("provider");
            }
            HttpRequest request = request(purpose, path, body, timeout, fallback);
            try {
                HttpResponse<String> response = sender.send(request);
                int status = response.statusCode();
                if (status / 100 == 2) {
                    return new Response(response.body(), fallback);
                }
                if (conversation) {
                    // отвергнутый запрос разбирают по той же паре файлов: текст
                    // ошибки не говорит, какое именно тело ему не понравилось
                    Transcript.save(body, response.body());
                }
                if (status == 429) {
                    // для контроля TPM: в теле видно, какой именно лимит выбран
                    log.warn("Эндпоинт ответил 429: {}", maskedBody(response.body()));
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
                    log.warn("Эндпоинт потребовал thought_signature — повторяю с маркером импортированной истории");
                    continue;
                }
                if (conversation && status == 400 && TOOL_CHOICE_REQUIRED.equals(body.path("tool_choice").asText())) {
                    if (fallback) fallbackToolChoiceUnsupported = true;
                    else toolChoiceUnsupported = true;
                    attempt--;
                    log.warn("Шлюз отверг принудительный вызов инструментов — повторяю без tool_choice");
                    continue;
                }
                String note = ErrorNote.of(maskedKeys(response.body()));
                LlmFailure failure = new LlmFailure(status,
                        "Ошибка эндпоинта (%d): %s".formatted(status, note),
                        purpose == Purpose.CHAT && !fallback
                                && (conversation || config.fallbackModel().isBlank())
                                && (status == 401 || status == 402 || status == 403));
                if (!failure.retryable()) {
                    if (fallback && retryPrimary) {
                        throw new LlmFailure(status, failure.getMessage(), false, true);
                    }
                    if (failure.requiresOperator()
                            || (purpose != Purpose.CHAT && (status == 401 || status == 402 || status == 403))) {
                        block(purpose, failure);
                    }
                    throw failure;
                }
                lastFailure = failure;
                if (status == 429 && rateLimited >= 1) {
                    // второй повтор только продлил бы бан: лимит считается по минутам
                    throw failure;
                }
                if (attempt < MAX_ATTEMPTS) {
                    Duration delay;
                    if (status == 429) {
                        rateLimited++;
                        delay = rateLimitDelay(response, attempt);
                    } else {
                        delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), attempt);
                    }
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

    private HttpRequest request(Purpose purpose, String path, ObjectNode body, Duration timeout, boolean fallback) {
        String url;
        String apiKey;
        if (fallback) {
            url = config.fallbackBaseUrl();
            apiKey = config.fallbackApiKey();
        } else if (purpose == Purpose.VISION) {
            url = visionBaseUrl;
            apiKey = visionApiKey;
        } else if (purpose == Purpose.EMBEDDING) {
            url = embeddingBaseUrl;
            apiKey = embeddingApiKey;
        } else {
            url = config.baseUrl();
            apiKey = config.apiKey();
        }
        try {
            return HttpRequest.newBuilder(URI.create((url.endsWith("/") ? url : url + "/") + path))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось собрать запрос", e);
        }
    }

    private LlmFailure blockedFor(Purpose purpose) {
        return switch (purpose) {
            case CHAT -> blocked;
            case VISION -> visionBlocked;
            case EMBEDDING -> embeddingBlocked;
        };
    }

    private void block(Purpose purpose, LlmFailure failure) {
        switch (purpose) {
            case CHAT -> {
                blocked = failure;
                if (failure.requiresOperator()) {
                    // общий адрес — общий ключ и общий платёжный счёт; свой
                    // провайдер у зрения или эмбеддингов отказ чата не закрывает
                    if (visionBaseUrl.equals(config.baseUrl())) {
                        visionBlocked = failure;
                    }
                    if (embeddingBaseUrl.equals(config.baseUrl())) {
                        embeddingBlocked = failure;
                    }
                }
            }
            case VISION -> visionBlocked = failure;
            case EMBEDDING -> embeddingBlocked = failure;
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
        long seconds = Math.max(2L * attempt, retryAfterSeconds(header));
        return clampDelay(seconds);
    }

    /** Сколько секунд просит выждать заголовок: число либо дата по RFC 1123. */
    private static long retryAfterSeconds(String header) {
        try {
            return Long.parseLong(header.strip());
        } catch (NumberFormatException ignored) {
            try {
                var date = java.time.ZonedDateTime.parse(header, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return Duration.between(java.time.Instant.now(), date.toInstant()).toSeconds();
            } catch (java.time.format.DateTimeParseException invalid) {
                return 0;
            }
        }
    }

    /** Тело ошибки для журнала: ключи вырезаны, разметка и управляющие символы тоже. */
    private String maskedBody(String body) {
        return ErrorNote.of(maskedKeys(body));
    }

    /** Все настроенные ключи, длинные первыми: короткий не должен испортить длинный. */
    private String maskedKeys(String text) {
        String masked = text == null ? "" : text;
        List<String> keys = new ArrayList<>();
        for (String key : new String[]{config.apiKey(), config.fallbackApiKey(), visionApiKey, embeddingApiKey}) {
            if (key != null && !key.isBlank()) keys.add(key);
        }
        keys.sort(java.util.Comparator.comparingInt(String::length).reversed());
        for (String key : keys) {
            masked = masked.replace(key, "[скрыто]");
        }
        return masked;
    }

    /**
     * Сколько ждать после 429. Сначала то, что назвал сам эндпоинт: retryDelay
     * из тела, затем Retry-After. Если молчит — пол в пять секунд: прежние две
     * только продлевали лимит.
     */
    private Duration rateLimitDelay(HttpResponse<String> response, int attempt) {
        java.util.Optional<Duration> stated = retryDelayFromBody(response.body());
        if (stated.isPresent()) {
            return clampDelay(stated.get().toSeconds());
        }
        long header = retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(""));
        if (header > 0) {
            return clampDelay(header);
        }
        return clampDelay(5L * attempt);
    }

    /** Срок из тела 429: Google RetryInfo в details или подсказка OpenAI в тексте. */
    private java.util.Optional<Duration> retryDelayFromBody(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
        java.util.Optional<Duration> stated = durationOf(root.path("retryDelay"))
                .or(() -> durationOf(root.path("error").path("retryDelay")))
                .or(() -> firstRetryDelay(root.path("error").path("details")))
                .or(() -> firstRetryDelay(root.path("details")));
        if (stated.isPresent()) {
            return stated;
        }
        java.util.regex.Matcher hint = TRY_AGAIN.matcher(root.path("error").path("message").asText(""));
        if (!hint.find()) {
            return java.util.Optional.empty();
        }
        double amount = Double.parseDouble(hint.group(1));
        long seconds = switch (hint.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "ms" -> (long) Math.ceil(amount / 1000);
            case "m" -> (long) Math.ceil(amount * 60);
            default -> (long) Math.ceil(amount);
        };
        return java.util.Optional.of(Duration.ofSeconds(seconds));
    }

    private static java.util.Optional<Duration> firstRetryDelay(JsonNode details) {
        if (!details.isArray()) {
            return java.util.Optional.empty();
        }
        for (JsonNode detail : details) {
            java.util.Optional<Duration> parsed = durationOf(detail.path("retryDelay"));
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<Duration> durationOf(JsonNode node) {
        if (!node.isTextual()) {
            return java.util.Optional.empty();
        }
        java.util.regex.Matcher matcher = GOOGLE_DELAY.matcher(node.asText().strip());
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Duration.ofSeconds((long) Math.ceil(Double.parseDouble(matcher.group(1)))));
    }

    private static Duration clampDelay(long seconds) {
        return Duration.ofSeconds(Math.clamp(seconds, 1, MAX_RETRY_SECONDS));
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
