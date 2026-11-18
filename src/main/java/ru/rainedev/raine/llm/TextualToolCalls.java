package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вызов инструмента, написанный текстом.
 * <p>
 * Модель изредка отвечает не в поле tool_calls, а строкой вида
 * {@code <call:send_telegram_message{text:привет}>} или
 * {@code tool call #send_telegram_message({"text":"привет"})}. Цикл такой ответ действием
 * не считал: сообщение было написано, а адресат его не увидел. Здесь текстовый
 * артефакт превращается в настоящий вызов, а служебная запись вырезается из
 * видимого текста.
 */
final class TextualToolCalls {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Имя инструмента: буквы, цифры и подчёркивания — как в самих инструментах. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** После такой запятой начинается следующий аргумент, а не продолжение значения. */
    private static final Pattern NEXT_KEY = Pattern.compile("^\\s*[\"']?[A-Za-z_][A-Za-z0-9_-]*[\"']?\\s*:");

    /** Служебная пометка перед аргументами в форме шлюза: `… json='{"text":"…"}'`. */
    private static final Pattern JSON_MARKER = Pattern.compile("(?i)\\bjson\\s*[=:]?\\s*[\"']?$");

    /**
     * Вызов, написанный строкой: модель копирует форму из примеров характера, а
     * шлюз добавляет своё пространство имён. Бывают оба разделителя — «tool call
     * #имя(...)» и «tool call: имя{...}»; скобка вокруг аргументов и сами
     * аргументы необязательны. Засчитывается только с начала строки, иначе
     * упомянутый в предложении вызов стал бы действием.
     */
    private static final Pattern LINE_CALL = Pattern.compile(
            "(?m)^[ \\t]*tool call[ \\t]*[ #:][ \\t]*(?:[A-Za-z0-9_]+:[ \\t]*)?([A-Za-z0-9_]+)"
                    + "[ \\t]*\\(?[ \\t]*\\{");

    /**
     * Хвосты служебной разметки вызовов. Модель дописывает их и рядом с
     * настоящими tool_calls, и когда вызов не состоялся; оставленные, они
     * разъезжаются по контексту и потом показываются в «Мысли». Обёртки
     * `tools`/`args` добавлены по дампу 23:59:04: целые блоки вызовов оттуда
     * разбираются, а пустая обёртка обязана исчезнуть вместе с ними.
     */
    private static final Pattern DANGLING =
            Pattern.compile("</?call>|</?output>|</?tool_call>|</?function_call>"
                    + "|</?tools>|</?args>|<tool\\s[^>]*>|</?tool>|<arg[^>]*>|</arg>");

    /**
     * `- call: send_telegram_message(text="…")` — вызов свёрнут в пункт списка,
     * как поля `- thought:` (дамп 23:58:12). Ни слова «tool call», ни `<call:`
     * в нём нет, и без этого разбора шаг сгорал целиком.
     */
    private static final Pattern DASH_CALL = Pattern.compile(
            "(?m)^[ \\t]*-[ \\t]*call[ \\t]*:[ \\t]*([A-Za-z0-9_]+)[ \\t]*\\((.*)\\)[ \\t]*$");

    /** Та же строка-мусор рядом: модель включает себе право на опечатки. */
    private static final Pattern DASH_JUNK = Pattern.compile(
            "(?m)^[ \\t]*-[ \\t]*allow_typos[ \\t]*:.*\\n?");

    /** `<tool name="имя"> … </tool>` из блока `<tools>` (дамп 23:59:04). */
    private static final Pattern XML_TOOL = Pattern.compile(
            "<tool[ \\t]+name=\"([A-Za-z0-9_]+)\"[ \\t]*>([\\s\\S]*?)</tool>");

    /** `<arg name="ключ">значение</arg>` внутри такого инструмента. */
    private static final Pattern XML_ARG = Pattern.compile(
            "<arg[ \\t]+name=\"([A-Za-z0-9_]+)\"[ \\t]*>([\\s\\S]*?)</arg>");

    /** Пара `ключ="значение"` или `ключ=текст` в аргументах вызова. */
    private static final Pattern NAMED_PAIR = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)[ \\t]*=[ \\t]*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*'|[^,]*)");

    private TextualToolCalls() {}

    record Parsed(String text, List<ToolCall> calls) {}

    /**
     * Разбирает ответ. Если настоящие вызовы уже есть, текст не трогается:
     * модель сделала всё правильно, и догадки ей не нужны.
     */
    static ChatResponse repair(ChatResponse response) {
        return repair(response, true);
    }

    /**
     * @param toolsOffered были ли инструменты в запросе. Без них синтетический
     *                     вызов некуда деть, и текст остаётся текстом
     */
    static ChatResponse repair(ChatResponse response, boolean toolsOffered) {
        if (!toolsOffered || response == null || response.choices() == null || response.choices().isEmpty()) {
            return response;
        }
        Message message = response.firstMessage().orElse(null);
        if (message == null || message.content() == null) {
            return response;
        }
        List<ToolCall> nativeCalls = message.toolCallsOrEmpty();
        // настоящий вызов — не повод оставлять в тексте хвосты его разметки, а
        // отсутствие вызова — не повод оставлять саму разметку: в логах шага
        // 17:19:50 </call> висел рядом с корректным tool_calls
        Parsed parsed = nativeCalls.isEmpty() ? parse(message.content()) : new Parsed(message.content(), List.of());
        String text = parsed.calls().isEmpty()
                ? DANGLING.matcher(parsed.text()).replaceAll("").strip()
                : parsed.text();
        if (parsed.calls().isEmpty() && text.equals(message.content())) {
            return response;   // разбирать и чистить было нечего
        }
        Message fixed = new Message(message.role(), text.isEmpty() ? null : text,
                nativeCalls.isEmpty() ? parsed.calls() : nativeCalls,
                message.toolCallId(), message.reasoningContent());
        List<ChatResponse.Choice> choices = new ArrayList<>(response.choices());
        ChatResponse.Choice first = choices.getFirst();
        choices.set(0, new ChatResponse.Choice(first.index(), fixed, first.finishReason()));
        return new ChatResponse(response.id(), response.model(), List.copyOf(choices), response.usage());
    }

    /**
     * @return текст без служебных тегов и вызовы, которые в нём были
     */
    static Parsed parse(String text) {
        if (text == null || text.isEmpty()) {
            return new Parsed(text == null ? "" : text, List.of());
        }
        Parsed xml = xmlCalls(text);
        Parsed tagged = taggedCalls(xml.text(), xml.calls());
        Parsed lines = lineCalls(tagged.text(), tagged.calls());
        Parsed dashes = dashCalls(lines.text(), lines.calls());
        if (dashes.calls().isEmpty()) {
            return dashes;
        }
        // вызовы разобраны — от их разметки не должно остаться хвостов: блоки
        // ушли вместе со своими строками, а обёртка <tools> жила отдельно
        return new Parsed(DANGLING.matcher(dashes.text()).replaceAll("").strip(), dashes.calls());
    }

    /**
     * Блок `<tools><tool name="имя"><args><arg name="ключ">значение</arg></args></tool></tools>`.
     * Значения остаются строками: числа длиннее 2^53 через double теряют младшие
     * цифры, а `sticker_id` — именно такой.
     */
    private static Parsed xmlCalls(String text) {
        Matcher matcher = XML_TOOL.matcher(text);
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder kept = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            ObjectNode args = MAPPER.createObjectNode();
            Matcher arg = XML_ARG.matcher(matcher.group(2));
            while (arg.find()) {
                args.put(arg.group(1), arg.group(2).strip());
            }
            calls.add(new ToolCall("text-call-" + UUID.randomUUID(), "function",
                    new ToolCall.Function(matcher.group(1), args.toString())));
            kept.append(text, cursor, matcher.start());
            cursor = matcher.end();
        }
        if (calls.isEmpty()) {
            return new Parsed(text, List.of());
        }
        kept.append(text, cursor, text.length());
        return new Parsed(kept.toString().strip(), List.copyOf(calls));
    }

    /**
     * Строки `- call: имя(ключ="значение", …)`: вызов становится настоящим,
     * строка уходит из текста вместе с соседней `- allow_typos:`.
     */
    private static Parsed dashCalls(String text, List<ToolCall> collected) {
        Matcher matcher = DASH_CALL.matcher(text);
        List<ToolCall> calls = new ArrayList<>(collected);
        StringBuilder kept = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            calls.add(new ToolCall("text-call-" + UUID.randomUUID(), "function",
                    new ToolCall.Function(matcher.group(1), namedArguments(matcher.group(2)))));
            kept.append(text, cursor, matcher.start());
            cursor = endOfLine(text, matcher.end());
        }
        if (calls.size() == collected.size()) {
            return new Parsed(text, collected);
        }
        kept.append(text, cursor, text.length());
        return new Parsed(DASH_JUNK.matcher(kept).replaceAll("").strip(), List.copyOf(calls));
    }

    /** Аргументы вида `text="привет", photo_filename="1.jpg"`; без кавычек — как есть. */
    private static String namedArguments(String raw) {
        ObjectNode args = MAPPER.createObjectNode();
        Matcher matcher = NAMED_PAIR.matcher(raw.strip());
        while (matcher.find()) {
            put(args, matcher.group(1), matcher.group(2).strip());
        }
        // у пустого набора ObjectNode.toString() и есть "{}" — отдельная ветка не нужна
        return args.toString();
    }

    /** Конец строки вместе с переносом: вызов из списка не оставляет пустой строки. */
    private static int endOfLine(String text, int from) {
        int i = skipSpaces(text, from);
        if (i >= text.length()) {
            return i;
        }
        if (text.startsWith("\r\n", i)) {
            return i + 2;
        }
        return text.charAt(i) == '\n' ? i + 1 : i;
    }

    private static Parsed taggedCalls(String text, List<ToolCall> collected) {
        if (!text.contains("<call:")) {
            return new Parsed(text, collected);
        }
        List<ToolCall> calls = new ArrayList<>(collected);
        StringBuilder kept = new StringBuilder();
        int cursor = 0;
        int search = 0;
        int start;
        while ((start = text.indexOf("<call:", search)) >= 0) {
            Found found = parseAt(text, start);
            if (found == null) {
                search = start + "<call:".length();
                continue;
            }
            kept.append(text, cursor, start);
            calls.add(found.call());
            cursor = found.end();
            search = found.end();
        }
        kept.append(text, cursor, text.length());
        return new Parsed(kept.toString().strip(), calls);
    }

    /**
     * Строки {@code tool call #name({...})}: вызов становится настоящим, а строка
     * уходит из видимого текста целиком, вместе со скобкой и переносом.
     */
    private static Parsed lineCalls(String text, List<ToolCall> collected) {
        Matcher matcher = LINE_CALL.matcher(text);
        List<ToolCall> calls = new ArrayList<>(collected);
        StringBuilder kept = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            int open = matcher.end() - 1;
            int close = closingBrace(text, open);
            if (close < 0) {
                continue;
            }
            calls.add(new ToolCall("text-call-" + UUID.randomUUID(), "function",
                    new ToolCall.Function(matcher.group(1), arguments(text.substring(open + 1, close)))));
            kept.append(text, cursor, matcher.start());
            cursor = endOfCallLine(text, close);
        }
        if (calls.size() == collected.size()) {
            return new Parsed(text, collected);
        }
        kept.append(text, cursor, text.length());
        return new Parsed(kept.toString().strip(), List.copyOf(calls));
    }

    /**
     * @return конец вызова в строке: закрывающая скобка аргументов необязательна,
     *         и если после неё в строке ничего не было, перенос съедается вместе с ней
     */
    private static int endOfCallLine(String text, int close) {
        int i = skipSpaces(text, close + 1);
        if (i < text.length() && text.charAt(i) == ')') {
            i = skipSpaces(text, i + 1);
        }
        if (i == text.length()) {
            return i;
        }
        if (text.charAt(i) == '\n') {
            return i + 1;
        }
        if (text.charAt(i) == '\r') {
            return text.startsWith("\r\n", i) ? i + 2 : i + 1;
        }
        return i;
    }

    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private record Found(ToolCall call, int end) {}

    private static Found parseAt(String text, int start) {
        int brace = text.indexOf('{', start + "<call:".length());
        if (brace < 0) {
            return null;
        }
        String name = nameOf(text.substring(start + "<call:".length(), brace));
        if (name.isEmpty()) {
            return null;
        }
        int close = closingBrace(text, brace);
        if (close < 0) {
            return null;
        }
        String arguments = arguments(text.substring(brace + 1, close));
        int end = close + 1;
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        if (end < text.length() && (text.charAt(end) == '\'' || text.charAt(end) == '"')) {
            // форма шлюза держит аргументы в кавычке: json='{"text":"…"}'
            end++;
        }
        while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
            end++;
        }
        if (end < text.length() && text.charAt(end) == '/') {
            end++;   // и закрывает тег сама: <call:…/>
        }
        if (end < text.length() && text.charAt(end) == '>') {
            end++;
        }
        if (text.startsWith("</call:", end)) {
            int after = text.indexOf('>', end);
            if (after > 0) {
                end = after + 1;
            }
        }
        return new Found(new ToolCall("text-call-" + UUID.randomUUID(), "function",
                new ToolCall.Function(name, arguments)), end);
    }

    /**
     * Пространство имён отбрасывается: имена инструментов живут без него.
     * Шлюз умеет писать ещё и `json='{"text":"…"}'` — пометка перед аргументами
     * тоже не часть имени, иначе вызов остаётся текстом и шаг сгорает впустую.
     */
    private static String nameOf(String raw) {
        String name = raw.strip();
        name = name.substring(name.lastIndexOf(':') + 1).strip();
        name = JSON_MARKER.matcher(name).replaceAll("").strip();
        return NAME.matcher(name).matches() ? name : "";
    }

    /** Закрывающая скобка ищется с оглядкой на вложенность и строки в кавычках. */
    private static int closingBrace(String text, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String arguments(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return "{}";
        }
        JsonNode proper = tryJson("{" + trimmed + "}");
        if (proper != null && proper.isObject()) {
            return proper.toString();
        }
        return loose(trimmed);
    }

    private static JsonNode tryJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Аргументы в этом формате пишутся без кавычек: {@code {text:привет, count:3}}.
     * Запятая внутри значения — часть текста, поэтому разделителем считается
     * только та, после которой идёт похожий на ключ кусок.
     */
    private static String loose(String raw) {
        ObjectNode node = MAPPER.createObjectNode();
        int cursor = 0;
        while (cursor < raw.length()) {
            int colon = colonAt(raw, cursor);
            if (colon < 0) {
                break;
            }
            String key = keyOf(raw.substring(cursor, colon));
            if (key.isEmpty()) {
                break;
            }
            int end = valueEnd(raw, colon + 1);
            put(node, key, raw.substring(colon + 1, end).strip());
            cursor = end + 1;
        }
        return node.toString();
    }

    private static int colonAt(String raw, int from) {
        char quote = 0;
        int depth = 0;
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int valueEnd(String raw, int from) {
        char quote = 0;
        int depth = 0;
        for (int i = from; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0 && NEXT_KEY.matcher(raw.substring(i + 1)).find()) {
                return i;
            }
        }
        return raw.length();
    }

    private static String keyOf(String raw) {
        String key = raw.strip();
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            JsonNode parsed = tryJson(key);
            return parsed != null && parsed.isTextual() ? parsed.asText() : key.substring(1, key.length() - 1);
        }
        if (key.length() >= 2 && key.startsWith("'") && key.endsWith("'")) {
            return key.substring(1, key.length() - 1);
        }
        return key;
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            JsonNode parsed = tryJson(value);
            node.put(key, parsed != null && parsed.isTextual() ? parsed.asText() : value.substring(1, value.length() - 1));
        } else if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            node.put(key, value.substring(1, value.length() - 1));
        } else if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            node.put(key, Boolean.parseBoolean(value));
        } else if ("null".equalsIgnoreCase(value)) {
            node.putNull(key);
        } else if (value.matches("-?\\d+")) {
            try {
                node.put(key, Long.parseLong(value));
            } catch (NumberFormatException tooBig) {
                node.put(key, value);
            }
        } else if (value.matches("-?\\d+\\.\\d+")) {
            node.put(key, Double.parseDouble(value));
        } else if (value.length() >= 2 && value.startsWith("{") && value.endsWith("}")) {
            JsonNode nested = tryJson(loose(value.substring(1, value.length() - 1)));
            if (nested != null) {
                node.set(key, nested);
            } else {
                node.put(key, value);
            }
        } else if (value.startsWith("[") && value.endsWith("]")) {
            JsonNode parsed = tryJson(value);
            if (parsed != null) {
                node.set(key, parsed);
            } else {
                node.put(key, value);
            }
        } else {
            node.put(key, value);
        }
    }
}
