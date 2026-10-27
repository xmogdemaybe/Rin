package ru.rainedev.raine.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.ErrorNote;
import ru.rainedev.raine.phone.Markup;

/** Поиск в интернете — для того, чего в дневнике быть не может: погода, новости, факты. */
public final class WebSearch {

    private static final Logger log = LoggerFactory.getLogger(WebSearch.class);

    static final int MAX_RESULTS = 5;

    /** Сколько чужого текста уходит в контекст на один результат. */
    static final int MAX_SNIPPET = 2000;

    private static final int MAX_TITLE = 300;
    private static final int MAX_URL = 2000;
    private static final int MAX_QUERY = 500;

    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final String url;
    private final String apiKey;

    public WebSearch(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** @return размеченные результаты или пояснение, почему их нет */
    public String search(String query) {
        if (!isAvailable()) {
            return "No web search available";
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("query", query);
            body.put("max_results", MAX_RESULTS);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(45))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String note = errorNote(response.body());
                log.warn("Веб-поиск вернул {}: {}", response.statusCode(), note);
                // Безопасная причина сохраняет разницу между квотой и отсутствием сети.
                return "Web search failed (%d): %s".formatted(response.statusCode(), note);
            }
            JsonNode envelope = mapper.readTree(response.body());
            if (envelope == null || !envelope.isObject()) {
                return unknownFormat();
            }
            // Наличие results, даже null, исключает откат к data.
            return render(envelope.has("results") ? envelope.get("results") : envelope.get("data"), query);
        } catch (JsonProcessingException e) {
            // Ошибка разбора может цитировать ответ сервера: наружу только известная причина.
            return unknownFormat();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Web search interrupted";
        } catch (Exception e) {
            String note = errorNote(e.getMessage());
            log.warn("Веб-поиск не удался: {}", note);
            return "Web search failed: " + note;
        }
    }

    private String errorNote(String text) {
        // Известный ключ скрываем до сокращения и очистки, независимо от его длины и формы.
        return ErrorNote.of(text == null ? null : text.replace(apiKey, "[скрыто]"));
    }

    private static String unknownFormat() {
        String note = ErrorNote.of("Unknown web search response format");
        log.warn("Веб-поиск не удался: {}", note);
        return "Web search failed: " + note;
    }

    /**
     * Заголовки и текст приходят с чужого сайта: без оглядки они закрывают
     * блок результата и продолжают разбор уже своей фразой.
     * <p>
     * Ограничен и размер: эндпоинт может вернуть не пять строк, а пять полных
     * страниц, а инструментный ответ уходит в контекст как есть. Простыня
     * посреди хода стоит ей всего разговора — контекст переполнится, и он
     * свалится в дневник.
     * <p>
     * Видна пакету: мерить размер чужого ответа можно и без поднятой сети.
     */
    static String render(JsonNode results, String query) {
        if (results == null || !results.isArray()) {
            return unknownFormat();
        }
        if (results.isEmpty()) {
            // запрос модель берёт из разговора, а разговор диктуют посторонние:
            // голое эхо вернуло бы чужую скопку уже готовым тегом
            return "Nothing found on the web for: " + bounded(Markup.body(query), MAX_QUERY);
        }
        // Проверяем весь массив до вывода: повреждение не становится пустой находкой,
        // даже если оно оказалось за пределом пяти результатов.
        for (JsonNode result : results) {
            if (!result.isObject() || !result.path("title").isTextual()
                    || !result.path("url").isTextual() || !result.path("content").isTextual()) {
                return unknownFormat();
            }
        }
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (JsonNode result : results) {
            if (kept == MAX_RESULTS) {
                log.warn("Веб-поиск вернул больше {} результатов — остальные отброшены", MAX_RESULTS);
                break;
            }
            String resultUrl = result.path("url").asText();
            boolean omitUrl = resultUrl.length() > MAX_URL;
            // Обрезанный URL выглядит рабочим, но ведёт уже не туда: опускаем целиком.
            out.append("<web_search_result title=\"%s\" url=\"%s\">\n%s%s\n</web_search_result>\n".formatted(
                    bounded(Markup.attribute(result.path("title").asText()), MAX_TITLE),
                    omitUrl ? "" : Markup.attribute(resultUrl),
                    omitUrl ? "[URL omitted: too long]\n" : "",
                    Markup.body(snippet(result.path("content").asText()))));
            kept++;
        }
        return out.toString();
    }

    private static String bounded(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /** Кусок чужой страницы: дальше смыслов уже нет, а токены есть. */
    private static String snippet(String content) {
        String text = content.strip();
        if (text.length() <= MAX_SNIPPET) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_SNIPPET);
        return (cut > 0 ? text.substring(0, cut) : text.substring(0, MAX_SNIPPET)) + "…";
    }
}
