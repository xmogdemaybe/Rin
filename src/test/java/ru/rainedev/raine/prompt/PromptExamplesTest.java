package ru.rainedev.raine.prompt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Примеры в промптах модель принимает за жизнь. Вечером 18.11 пересказ дневника
 * взял вымышленное «Дима» из примеров `system.md` и вставил его в свободные поля
 * записи: «Отвечаю на сообщения димы про арты», «retrieval cues: … рисунки
 * димы». Через несколько часов эта страница всплыла по вспоминанию, и бот начал
 * звать владельца Димой — при том что в самом разговоре такого имени не было
 * ни разу (в дампе 23:45:35: 7 вхождений в системном промпте, 0 во входе).
 * <p>
 * Вторая дыра того же вечера: примеры показывают вызов строкой
 * `tool call #name({…})`, и модель на `auto` периодически переносит этот
 * синтаксис в текст ответа (`- call: …`, `<tools><tool name=…>`) — шаг сгорает,
 * человек ждёт. Теперь формат назван условным и прямо запрещён в тексте.
 */
class PromptExamplesTest {

    private static final Path SYSTEM = Path.of("prompts-example", "system.md");
    private static final Path DIARY = Path.of("prompts-example", "diary_save.md");
    private static final Pattern SENDER = Pattern.compile("sender=\"([^\"]*)\"");

    /** Метки-заглушки: настоящее имя человека может взять только из сообщений. */
    private static final List<String> ALLOWED_SENDERS = List.of("${PAPIK_NAME}", "Собеседник", "Незнакомец");

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }

    /**
     * Проверяем все образцовые промпты сразу: 41 пример вызова живёт не в
     * system.md, а в character_base.md, и имя могло зашиться там же.
     */
    @Test
    void exampleChatsNeverCarryAnInventedPersonName() throws IOException {
        try (var files = Files.list(Path.of("prompts-example"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String text = Files.readString(path);
                Matcher matcher = SENDER.matcher(text);
                while (matcher.find()) {
                    assertTrue(ALLOWED_SENDERS.contains(matcher.group(1)),
                            path + ": пример учит отвечать на чужое имя, которое модель примет за настоящее: "
                                    + matcher.group(1));
                }
                assertFalse(text.contains("Дима"), path + ": имя из примеров уехало в дневник 18.11");
            }
        }
    }

    @Test
    void theShorthandForCallsIsMarkedAsShorthand() throws IOException {
        String text = read(SYSTEM);

        assertTrue(text.contains("is only a shorthand"), "пример больше не выдаётся за способ отвечать: " + text);
        assertTrue(text.contains("Writing a call into the message text is forbidden"), text);
        assertTrue(text.contains("`- call: name(...)`"), "списочная форма названа прямо (дамп 23:58:12)");
        assertTrue(text.contains("`<tools>`/`<tool name=\"...\">`"), "XML-форма названа прямо (дамп 23:59:04)");
        assertTrue(text.contains("allow_typos"), "служебная строка про опечатки тоже запрещена");
        assertTrue(text.contains("native tool_calls"), "противопоставление должно быть в тексте");
    }

    @Test
    void theRetellerTakesNamesOnlyFromTheConversation() throws IOException {
        String text = read(DIARY);

        assertTrue(text.contains("Names come only from the conversation"), "правило пропало: " + text);
        assertTrue(text.contains("Never borrow a name from the examples"), text);
        assertTrue(text.contains("never invent one"), "угадывание имени запрещено словами");
        assertTrue(text.contains("собеседник"), "подсказка, чем заменить безымянного: " + text);
    }
}
