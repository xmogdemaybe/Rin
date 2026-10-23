package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Набор инструментов, доступных прямо сейчас.
 * <p>
 * Состав меняется по ходу дела: пока чат не открыт, отправлять нечего,
 * а в канале отправка недоступна вовсе. Ограничение правами, а не просьбой
 * в промпте: инструмент, которого нет, нельзя уговорить появиться.
 */
public final class Toolbox {

    /** Ими модель завершает ход: «мне больше нечего делать». */
    public static final Set<String> FINISHING = Set.of("wait", "pause");

    private static final Logger log = LoggerFactory.getLogger(Toolbox.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Чем модель заменяет текст чужой ошибки: Telegram отвечает подробностями,
     * в которых бывает всё про аккаунт — номер, имя сессии, чужие идентификаторы.
     * Для решения ей довольно знать, что не вышло; подробности остаются в журнале.
     */
    static final String NOTHING_WORKED = "Не получилось. Попробуй позже или выбери другой путь.";

    public static final class PartialFailure extends LowQualityException {
        private final List<ru.rainedev.raine.llm.Message> results;
        public PartialFailure(String reason, List<ru.rainedev.raine.llm.Message> results) {
            super(reason);
            this.results = List.copyOf(results);
        }
        public List<ru.rainedev.raine.llm.Message> results() { return results; }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Действия разрешены по умолчанию: снимает их только явная настройка владельца. */
    private boolean actionsAllowed = true;

    public Toolbox(Tool... initial) {
        for (Tool tool : initial) {
            add(tool);
        }
    }

    /**
     * @param allowed ложь выкидывает из набора всё, что меняет Telegram. Право
     *                можно включить в любой момент: уже положенные действия уходят
     *                вместе с ним, иначе порядок вызовов решал бы, что видит модель
     */
    public Toolbox actionsAllowed(boolean allowed) {
        synchronized (tools) {
            actionsAllowed = allowed;
            if (!allowed) {
                tools.entrySet().removeIf(entry -> entry.getValue().mutates());
            }
        }
        return this;
    }

    public boolean allowsActions() {
        synchronized (tools) {
            return actionsAllowed;
        }
    }

    public Toolbox add(Tool tool) {
        synchronized (tools) {
            if (tool.mutates() && !actionsAllowed) {
                log.debug("Инструмент {} не предлагаю: режим только чтения", tool.name());
                return this;
            }
            tools.put(tool.name(), tool);
        }
        return this;
    }

    /** Инструмент выключается настройкой на ходу: снять, не трогая остальных. */
    public boolean remove(String name) {
        synchronized (tools) {
            return tools.remove(name) != null;
        }
    }

    public boolean isEmpty() {
        synchronized (tools) {
            return tools.isEmpty();
        }
    }

    public Set<String> names() {
        synchronized (tools) {
            return Set.copyOf(tools.keySet());
        }
    }

    /** Снимок набора: тумблеры меняются на ходу, чужой шаг не должен этого заметить. */
    public java.util.Collection<Tool> all() {
        synchronized (tools) {
            return List.copyOf(tools.values());
        }
    }

    public ArrayNode asJson() {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (Tool tool : all()) {
            array.add(tool.asJson());
        }
        return array;
    }

    /**
     * Выполняет вызовы по порядку и возвращает ответы для контекста.
     * <p>
     * Технические ошибки не роняют ход: модель видит, что не вышло, и решает,
     * что делать дальше. {@link LowQualityException} пробрасывается —
     * его обрабатывает цикл.
     */
    public List<ru.rainedev.raine.llm.Message> invoke(List<ToolCall> calls) {
        return invoke(calls, this::add);
    }

    /**
     * @param appeared куда складывать инструменты, появившиеся по ходу дела.
     *                 Набор пересобирается на каждом шаге, поэтому класть их
     *                 только сюда мало: открытый чат должен оставаться открытым
     *                 до конца хода, иначе на следующем шаге вернутся инструменты
     *                 прежнего чата и сообщение уйдёт не туда
     */
    public List<ru.rainedev.raine.llm.Message> invoke(List<ToolCall> calls, java.util.function.Consumer<Tool> appeared) {
        // инструменту бывает важно знать, один ли он в этом шаге
        CurrentStep.set(calls);
        try {
            List<ru.rainedev.raine.llm.Message> results = new ArrayList<>();
            for (int index = 0; index < calls.size(); index++) {
                ToolCall call = calls.get(index);
                try {
                    results.add(ru.rainedev.raine.llm.Message.toolResult(call.id(), clean(invokeOne(call, appeared))));
                } catch (LowQualityException failure) {
                    if (results.isEmpty()) throw failure;
                    results.add(ru.rainedev.raine.llm.Message.toolResult(call.id(), "Rejected: " + failure.getMessage()));
                    for (int skipped = index + 1; skipped < calls.size(); skipped++) {
                        results.add(ru.rainedev.raine.llm.Message.toolResult(calls.get(skipped).id(),
                                "Not executed: previous action was rejected. Decide again."));
                    }
                    throw new PartialFailure(failure.getMessage(), results);
                }
            }
            return results;
        } finally {
            CurrentStep.clear();
        }
    }

    private String invokeOne(ToolCall call, java.util.function.Consumer<Tool> appeared) {
        Tool tool;
        synchronized (tools) {
            tool = tools.get(call.name());
        }
        if (tool == null) {
            return "Инструмент '%s' сейчас недоступен. Доступны: %s".formatted(call.name(), String.join(", ", names()));
        }
        try {
            // инструменты, появившиеся по ходу дела, живут в этом же наборе
            // и исчезнут вместе с ним
            return tool.handler().call(parse(call.arguments()), appeared);
        } catch (LowQualityException e) {
            throw e;
        } catch (RuntimeException e) {
            Fatal.check(e);
            log.warn("Инструмент {} не отработал: {}", call.name(), e.toString());
            return NOTHING_WORKED;
        }
    }

    /**
     * Управляющие символы в результате инструмента модели ни к чему: они приходят
     * из чужих текстов и имён файлов, а в контексте выглядят как мусор.
     */
    private static String clean(String result) {
        if (result == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(result.length());
        result.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || c >= 0x20) {
                out.appendCodePoint(c);
            }
        });
        return out.toString();
    }

    private static JsonNode parse(String arguments) {
        try {
            return MAPPER.readTree(arguments);
        } catch (Exception e) {
            // модель иногда присылает пустую строку или мусор вместо JSON
            return JsonNodeFactory.instance.objectNode();
        }
    }
}
