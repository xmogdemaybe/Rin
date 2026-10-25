package ru.rainedev.raine.tools;

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

    private final ObjectMapper mapper = new ObjectMapper();
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
                String note = ErrorNote.of(response.body());
                log.warn("Веб-поиск вернул {}: {}", response.statusCode(), note);
                // причина передаётся как есть: «кончилась квота» и «нет сети» —
                // это разные вещи, и решать, что делать дальше, ей
                return "Web search failed (%d): %s".formatted(response.statusCode(), note);
            }
            return render(mapper.readTree(response.body()).path("results"), query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Web search interrupted";
        } catch (Exception e) {
            // сюда же приходит и ответ, который не разобрался: текст такой ошибки
            // цитирует чужую страницу, а её кусок ничего не объясняет и только
            // занимает место в контексте
            log.warn("Веб-поиск не удался: {}", e.getMessage());
            return "Web search failed: " + ErrorNote.of(e.getMessage());
        }
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
        if (!results.isArray() || results.isEmpty()) {
            // запрос модель берёт из разговора, а разговор диктуют посторонние:
            // голое эхо вернуло бы чужую скопку уже готовым тегом
            return "Nothing found on the web for: " + Markup.body(query);
        }
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (JsonNode result : results) {
            if (kept == MAX_RESULTS) {
                log.warn("Веб-поиск вернул больше {} результатов — остальные отброшены", MAX_RESULTS);
                break;
            }
            out.append("<web_search_result title=\"%s\" url=\"%s\">\n%s\n</web_search_result>\n".formatted(
                    Markup.attribute(result.path("title").asText("")),
                    Markup.attribute(result.path("url").asText("")),
                    Markup.body(snippet(result.path("content").asText("")))));
            kept++;
        }
        return out.toString();
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
