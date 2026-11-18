package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Разметка памяти в одном месте: как она собирается и как её вырезают все,
 * кто получает разговор с вклейками. Раньше генератор закрывал блок длинным
 * тегом, а вырезание искало короткий, — и сообщение вместе со словами человека
 * пропадало из пересказа.
 */
class DiaryMarkupTest {

    /** Запоминает, что именно уходило модели, и отдаёт заготовленные ответы. */
    private static final class Scripted implements LlmClient {
        final List<Message> lastHistory = new ArrayList<>();
        final Deque<String> answers = new ArrayDeque<>();
        double[] vector = new double[] {1, 0, 0};

        Scripted answers(String... texts) {
            answers.addAll(List.of(texts));
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            lastHistory.clear();
            lastHistory.addAll(history);
            String text = answers.isEmpty() ? "память: жду ответа от xmogde" : answers.removeFirst();
            Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return vector;
        }
    }

    private static Diary diaryWith(Path dir, String body) {
        Diary diary = new Diary(dir);
        diary.save(body, new double[] {1, 0, 0});
        return diary;
    }

    /** Тот самый старый вид: открытие с атрибутами, закрытие с атрибутами. */
    @Test
    void stripsTheLegacyLongClosingTag() {
        String legacy = "<your_diary_page additional_context just_for_reasoning no_plagiarism no_copy>\n"
                + "[from: your private chat with your person]\n"
                + "мы ездили на Байкал\n"
                + "</your_diary_page additional_context just_for_reasoning no_plagiarism no_copy>\n\n"
                + "собеседник: «ну как ты там»";

        assertEquals("собеседник: «ну как ты там»", DiaryMarkup.strip(legacy).strip());
    }

    @Test
    void stripsTheShortPairTheTestsAndPromptsUse() {
        String text = "<your_diary_page>вы ездили на Байкал</your_diary_page>\nтебе написали";

        assertEquals("тебе написали", DiaryMarkup.strip(text).strip());
    }

    /** Незакрытый блок уносит остаток — как прежнее вырезание, иначе обрывок тега сойдёт за реплику. */
    @Test
    void unclosedPageTakesTheRestOfTheMessage() {
        String text = "привет\n<your_diary_page additional_context>мы ездили на Байкал";

        assertEquals("привет", DiaryMarkup.strip(text).strip());
    }

    /** Похожее имя — не наш блок: вырезать его права нет. */
    @Test
    void leavesLookalikeTagsAlone() {
        String text = "<your_diary_pageX>что-то</your_diary_pageX> реплика";

        assertEquals(text, DiaryMarkup.strip(text));
    }

    @Test
    void generatedPageClosesShortAndStripsWhole() {
        String page = DiaryMarkup.page("[from: your private chat with your person]", "мы ездили на Байкал");

        assertTrue(page.startsWith("<your_diary_page additional_context"), page);
        assertTrue(page.contains("</your_diary_page>\n\n"), "закрытие обязано быть коротким: " + page);
        assertFalse(page.contains("</your_diary_page "), page);
        // страница — не слова разговора: вырезается целиком, вместе со своим текстом
        assertTrue(DiaryMarkup.strip(page).isBlank(), DiaryMarkup.strip(page));
    }

    /** Повод порыва собирается тем же контрактом и вырезается им же. */
    @Test
    void impulseReasonIsStrippableWhole() {
        String reason = DiaryMarkup.reason("xmogde вчера возился с сервером");

        assertTrue(reason.startsWith("<your_diary_page just_for_reasoning"), reason);
        assertTrue(reason.contains("</your_diary_page>\n\n"), reason);
        assertTrue(DiaryMarkup.strip(reason).isBlank(), DiaryMarkup.strip(reason));
    }

    /**
     * Сквозная проверка настоящим выводом памяти: страница, вклеенная в
     * сообщение, исчезает из пересказа и из сводки средней памяти, а слова
     * человека остаются.
     */
    @Test
    void realMemoryOutputLeavesNoMarkupAnywhere(@TempDir Path dir) {
        String said = "xmogde: «ты где пропала»";
        DiaryMemory memory = new DiaryMemory(diaryWith(dir, "мы ездили на Байкал"), new Scripted(), 4000);
        String recalled = memory.recall(List.of(Message.user(said)), Audience.STRANGER);
        assertFalse(recalled.isEmpty(), "дневник с подходящей записью обязан вспомниться");

        List<Message> glued = List.of(Message.user(recalled + said), Message.assistant("иду на кухню"));

        Scripted writer = new Scripted().answers("Мы ездили на Байкал, было холодно.");
        new DiaryWriter(new Diary(dir), writer, "запиши дневник", 0.97).save("промпт", glued);
        String sent = writer.lastHistory.toString();
        assertFalse(sent.contains("your_diary_page"), "разметка не едет в пересказ: " + sent);
        assertFalse(sent.contains("Байкал"), "вспомненное — не часть разговора: " + sent);
        assertTrue(sent.contains("ты где пропала"), "слова человека обязаны остаться: " + sent);

        Scripted working = new Scripted();
        Path file = dir.resolve("working_memory.md");
        new WorkingMemory(file, working, "Raine").update("промпт", glued);
        String history = working.lastHistory.toString();
        assertFalse(history.contains("your_diary_page"), "вклейки не портят трёхдневную сводку: " + history);
        assertTrue(history.contains("ты где пропала"), history);
        assertTrue(glued.get(0).content().contains("your_diary_page"),
                "исходный список править нельзя — его берёт дневник");
    }

    /** Пустая страница — не воспоминание: вырезается в ноль, а не в пару тегов. */
    @Test
    void emptyPageStripsToNothing() {
        assertEquals("", DiaryMarkup.strip(DiaryMarkup.page("", "")).strip());
    }
}
