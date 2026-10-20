package ru.rainedev.raine.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Клиент к языковой модели. Отдельный интерфейс — чтобы в тестах подменять заглушкой. */
public interface LlmClient {

    /** Обычный режим: модель сама решает, звать инструмент или писать текст. */
    String TOOL_CHOICE_AUTO = "auto";

    /** Принудительный режим: текст запрещён, модель обязана вызвать инструмент. */
    String TOOL_CHOICE_REQUIRED = "required";

    /**
     * @param systemPrompt системный промпт целиком
     * @param history      диалог; последним обычно идёт уведомление
     * @param tools        описания инструментов в формате OpenAI, может быть пустым
     */
    ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools);

    /** То же с явным выбором режима инструментов. */
    default ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools, String toolChoice) {
        return chat(systemPrompt, history, tools);
    }

    /** Вектор для поиска по дневнику. */
    double[] embedding(String input);

    /**
     * Описывает картинку словами.
     *
     * @param model какой моделью смотреть — дешёвой или основной
     */
    default String describeImage(String model, String systemPrompt, String context, byte[] image) {
        throw new UnsupportedOperationException("Зрение не подключено");
    }
}
