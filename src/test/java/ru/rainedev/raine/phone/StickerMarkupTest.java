package ru.rainedev.raine.phone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Как модель видит присланный стикер.
 * <p>
 * Номер стикера нужен, чтобы его отправить, а короткое имя пака — чтобы его
 * добавить: {@code sticker_pack} зовётся именно именем из ссылки. Без имени
 * просьба «добавь этот стикерпак», присланная вместе со стикером, нечем
 * выполнима, и она так и оставалась невыполненным обещанием.
 */
class StickerMarkupTest {

    private static TdApi.MessageContent sticker(long id, long setId, String emoji) {
        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.id = id;
        sticker.setId = setId;
        sticker.emoji = emoji;
        TdApi.MessageSticker content = new TdApi.MessageSticker();
        content.sticker = sticker;
        return content;
    }

    @Test
    void incomingStickerCarriesItsPack() {
        String line = MessageContent.describe(sticker(5357182711972790430L, 7L, "🥴"),
                setId -> setId == 7 ? "thatcursedrat" : "");

        assertTrue(line.contains("sticker_id=5357182711972790430"), line);
        assertTrue(line.contains("pack=\"thatcursedrat\""),
                "по имени пак и открывают: " + line);
    }

    @Test
    void anUnknownPackLeavesTheLineAsItWas() {
        AtomicInteger asked = new AtomicInteger();

        String line = MessageContent.describe(sticker(1L, 7L, "🙂"), setId -> {
            asked.incrementAndGet();
            return "";
        });

        assertFalse(line.contains("pack="), "пустой атрибут читается как сломанный: " + line);
        assertEquals(1, asked.get());
        assertEquals("[стикер sticker_id=1] 🙂", line);
    }

    @Test
    void aStickerWithoutAPackAsksNobody() {
        AtomicInteger asked = new AtomicInteger();

        String line = MessageContent.describe(sticker(1L, 0L, "🙂"), setId -> {
            asked.incrementAndGet();
            return "не должно случиться";
        });

        assertEquals(0, asked.get(), "нулевой номер пака — не вопрос к Telegram");
        assertFalse(line.contains("pack="), line);
    }

    @Test
    void theOldOneArgumentFormStaysQuiet() {
        assertFalse(MessageContent.describe(sticker(1L, 7L, "🙂")).contains("pack="),
                "разбор без провайдера имён не выдумывает их сам");
    }

    @Test
    void historyMarkupNeverAsksTelegram() {
        assertEquals("", MediaDescriber.NONE.packName(7),
                "краткая история обязана оставаться офлайн: в ней десятки чужих стикеров из незнакомых паков");
    }

    private static TdApi.Message sent(long id, TdApi.MessageContent content, long from) {
        TdApi.Message message = new TdApi.Message();
        message.id = id;
        message.senderId = new TdApi.MessageSenderUser(from);
        message.date = 1_700_000_000;
        message.content = content;
        return message;
    }

    /** Разметка спрашивает имя пака тем же путём, что и описание вложения. */
    private static MessageFormatter withPacks(java.util.function.LongFunction<String> packs) {
        return new MessageFormatter(id -> "papik", MessageFormatter.ReplyLookup.NONE,
                new MediaDescriber() {
                    @Override
                    public String describe(TdApi.Message message) {
                        return "";
                    }

                    @Override
                    public String packName(long setId) {
                        return packs.apply(setId);
                    }
                });
    }

    private static final MessageFormatter.ChatView VIEW = new MessageFormatter.ChatView(9L, 0L);

    @Test
    void theScreenCarriesThePackName() {
        String markup = withPacks(setId -> setId == 7 ? "thatcursedrat" : "")
                .format(sent(1, sticker(5357182711972790430L, 7L, "🥴"), 7), VIEW);

        assertTrue(markup.contains("pack=\"thatcursedrat\""), markup);
    }

    /**
     * Просьба «добавь этот стикерпак» приходит ответом на сам стикер, и цитата
     * обязана нести имя пака: без неё она снова остаётся невыполнимой.
     */
    /** Свободный текст — так, как его приносит Telegram: содержимое в FormattedText. */
    private static TdApi.MessageContent words(String text) {
        TdApi.MessageContent content = new TdApi.MessageText();
        ((TdApi.MessageText) content).text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        return content;
    }

    @Test
    void theCitationCarriesThePackName() {
        TdApi.Message quoted = sent(300, sticker(123L, 7L, "🥴"), 7);
        TdApi.Message answer = sent(600, words("Дабавишь этот стикерпак?"), 7);
        TdApi.MessageReplyToMessage reply = new TdApi.MessageReplyToMessage();
        reply.messageId = 300L;
        answer.replyTo = reply;
        MessageFormatter asking = new MessageFormatter(id -> "papik",
                (chatId, messageId) -> Optional.of(quoted),
                new MediaDescriber() {
                    @Override
                    public String describe(TdApi.Message message) {
                        return "";
                    }

                    @Override
                    public String packName(long setId) {
                        return setId == 7 ? "thatcursedrat" : "";
                    }
                });

        String markup = asking.format(answer, VIEW);

        assertTrue(markup.contains("<reply_to"), markup);
        assertTrue(markup.contains("pack=\"thatcursedrat\""),
                "имя пака нужно ровно там, где о пак и просят: " + markup);
    }

    @Test
    void aBriefHistoryNeverAsksForNames() {
        AtomicInteger asked = new AtomicInteger();

        String brief = withPacks(setId -> {
            asked.incrementAndGet();
            return "thatcursedrat";
        }).formatBriefly(sent(1, sticker(2L, 7L, "🙂"), 7), VIEW);

        assertEquals(0, asked.get(), "открытие старого чата с десятком чужих стикеров не выстраивает вопросов к Telegram");
        assertFalse(brief.contains("pack="), brief);
    }
}
