package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Что модель видит вместо упавшего инструмента. Проверить это можно без
 * Telegram: важно не то, чем именно всё сломалось, а то, какие слова дойдут
 * до разговора.
 */
class ToolboxFailureTest {

    /** Ошибка Telegram вживую: в ней данные аккаунта, а не только причина. */
    private static final String TELEGRAM_DETAILS =
            "Error 400: PHONE_NUMBER_INVALID — phone number +79991234567"
                    + " of session «mobile-desktop» is not activated";

    @Test
    void accountDetailsStayOutOfTheConversation() {
        String seen = invoke(Tool.simple("peek", "peek", arguments -> {
            throw new IllegalStateException(TELEGRAM_DETAILS);
        }));

        assertFalse(seen.contains("+79991234567"), seen);
        assertFalse(seen.contains("mobile-desktop"), seen);
        assertFalse(seen.contains("400"), seen);
    }

    @Test
    void theModelStillLearnsThatNothingCameOfIt() {
        // молчание здесь хуже отказа: пустой ответ модель прочла бы как
        // «действие выполнено», и повторила бы его ещё раз
        String seen = invoke(Tool.simple("peek", "peek", arguments -> {
            throw new IllegalStateException(TELEGRAM_DETAILS);
        }));

        assertEquals("Не получилось. Попробуй позже или выбери другой путь.", seen);
    }

    @Test
    void aToolThatWorkedKeepsItsOwnWords() {
        String seen = invoke(Tool.simple("peek", "peek", arguments -> "чат открыт"));

        assertEquals("чат открыт", seen);
    }

    @Test
    void aRejectedStepStillReachesTheLoop() {
        // LowQualityException — не техническая ошибка: цикл на него строит
        // весь свой ответ, и заглушить его значит потерять ход
        Toolbox tools = new Toolbox(Tool.simple("peek", "peek", arguments -> {
            throw new LowQualityException("слишком похоже на прошлый раз");
        }));

        assertThrows(LowQualityException.class, () -> tools.invoke(List.of(call("peek"))));
    }

    private static String invoke(Tool tool) {
        List<Message> results = new Toolbox(tool).invoke(List.of(call(tool.name())));
        assertEquals(1, results.size());
        return results.get(0).content();
    }

    private static ToolCall call(String name) {
        return new ToolCall("call-1", "function", new ToolCall.Function(name, "{}"));
    }
}
