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

    /**
     * Вызов с решёткой — форму модель копирует из примеров характера. Скобка вокруг
     * аргументов необязательна: в логах бывают обе. Засчитывается только с начала
     * строки, иначе упомянутый в предложении вызов стал бы действием.
     */
    private static final Pattern LINE_CALL =
            Pattern.compile("(?m)^[ \\t]*tool call #[ \\t]*([A-Za-z0-9_]+)[ \\t]*\\(?[ \\t]*\\{");

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
        if (message == null || message.content() == null || !message.toolCallsOrEmpty().isEmpty()) {
            return response;
        }
        Parsed parsed = parse(message.content());
        if (parsed.calls().isEmpty()) {
            return response;
        }
        Message fixed = new Message(message.role(), parsed.text().isEmpty() ? null : parsed.text(),
                parsed.calls(), message.toolCallId(), message.reasoningContent());
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
        Parsed tagged = taggedCalls(text);
        return lineCalls(tagged.text(), tagged.calls());
    }

    private static Parsed taggedCalls(String text) {
        if (!text.contains("<call:")) {
            return new Parsed(text, List.of());
        }
        List<ToolCall> calls = new ArrayList<>();
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

    /** Пространство имён отбрасывается: имена инструментов живут без него. */
    private static String nameOf(String raw) {
        String name = raw.strip();
        name = name.substring(name.lastIndexOf(':') + 1).strip();
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
