package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Текстовые лица в переписке. Вечером 30 октября вышло восемь реплик подряд,
 * и в каждой — «:3», «>_<» или «<3»: лицо превратилось в подпись, а не в
 * исключение. Здесь проверяются только счётчик и зачистка — никаких словарей
 * про «милый это момент или нет».
 */
class KaomojiTest {

    /** Чат, в котором копится бюджет. */
    private static final long CHAT = 1L;

    /** Сколько реплик должно пройти, чтобы лицо вернулось. */
    private static final int EVERY = 4;

    /** Снимок, к которому подписывают реплику. */
    private static final String PHOTO = "shot.webp";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Kaomoji kaomoji = new Kaomoji();

    @Test
    void firstFaceOfAFreshChatIsAllowed() {
        assertEquals("спокойной ночи, соня <3", kaomoji.gate(CHAT, "спокойной ночи, соня <3", EVERY));
    }

    @Test
    void ordinaryTextIsLeftAlone() {
        assertEquals("собрался и иду", kaomoji.gate(CHAT, "собрался и иду", EVERY));
        assertEquals("кофе остыл", kaomoji.gate(CHAT, "кофе остыл", EVERY));
    }

    @Test
    void aFaceIsCutOutWhileTheChatCooldownRuns() {
        kaomoji.gate(CHAT, "это что за чудо >_<", EVERY);

        assertEquals("это что за чудо лопоухое",
                kaomoji.gate(CHAT, "это что за чудо >_< лопоухое", EVERY));
    }

    @Test
    void facesComeRarelyAndNeverVanish() {
        // самое заметное в наводнении — лицо в каждой реплике; запретом целиком
        // это не лечится, лечится редкостью
        int sent = 0;
        int withFace = 0;
        for (int i = 0; i < 12; i++) {
            String piece = kaomoji.gate(CHAT, "вот так " + i + " >_<", EVERY);
            sent++;
            if (piece.contains(">_<")) {
                withFace++;
            }
        }

        assertTrue(withFace > 0, "лица не должно было исчезнуть совсем");
        assertTrue(withFace <= sent / 3,
                "лицо должно появляться редко: " + withFace + " из " + sent);
    }

    @Test
    void theChatCooldownFillsUpAndLetsTheFaceBack() {
        kaomoji.gate(CHAT, "первая >_<", EVERY);
        for (int i = 0; i < EVERY; i++) {
            kaomoji.gate(CHAT, "обычная реплика " + i, EVERY);
        }

        assertEquals("снова >_<", kaomoji.gate(CHAT, "снова >_<", EVERY));
    }

    @Test
    void trailingFacesLeaveNoTrailingSpace() {
        kaomoji.gate(CHAT, "признаю :3", EVERY);

        assertEquals("мило, признаю", kaomoji.gate(CHAT, "мило, признаю :3 <3", EVERY));
    }

    @Test
    void bracketsLeftEmptyAreCutOutToo() {
        kaomoji.gate(CHAT, "есть :3", EVERY);

        assertEquals("показывай скорее", kaomoji.gate(CHAT, "показывай ( :3 ) скорее", EVERY));
    }

    @Test
    void punctuationDoesNotDriftAwayFromTheWordBeforeIt() {
        kaomoji.gate(CHAT, "иди сюда <3", EVERY);

        assertEquals("хватит. иди сюда", kaomoji.gate(CHAT, "хватит >_< . иди сюда", EVERY));
    }

    @Test
    void aLineOfNothingButAFaceHasNothingToSend() {
        kaomoji.gate(CHAT, "иди сюда :3", EVERY);

        assertEquals("", kaomoji.gate(CHAT, ":3", EVERY),
                "от реплики-лица не должен остаться обрывок, кормящий тот же поток");
    }

    @Test
    void aTimeOrAPortIsNotAFace() {
        kaomoji.gate(CHAT, "обниму :3", EVERY);

        assertEquals("встретимся в 12:30", kaomoji.gate(CHAT, "встретимся в 12:30", EVERY));
        assertEquals("будильник на 10:3", kaomoji.gate(CHAT, "будильник на 10:3", EVERY));
        assertEquals("не поднимается localhost:3000", kaomoji.gate(CHAT, "не поднимается localhost:3000", EVERY));
    }

    @Test
    void everyChatKeepsItsOwnCooldown() {
        kaomoji.gate(1L, "тебе :3", EVERY);

        assertEquals("и тебе :3", kaomoji.gate(2L, "и тебе :3", EVERY),
                "в другом чате лицо ещё не потрачено");
    }

    @Test
    void zeroTurnsTheCounterOff() {
        for (int i = 0; i < 5; i++) {
            assertEquals("снова :3", kaomoji.gate(CHAT, "снова :3", 0));
        }
    }

    @Test
    void aForgottenChatMeetsTheFaceAsAFreshOne() {
        kaomoji.gate(1L, "лицо :3", EVERY);
        for (int chat = 2; chat <= Kaomoji.MAX_CHATS + 1; chat++) {
            kaomoji.gate(chat, "реплика без лица " + chat, EVERY);
        }

        assertEquals("снова :3", kaomoji.gate(1L, "снова :3", EVERY));
    }

    @Test
    void sendingUsesTheCooldownAtAll() {
        TelegramTools tools = tools();

        assertEquals(List.of("привет :3"), tools.piecesToSend(CHAT, "привет :3"));
        for (int i = 0; i < EVERY; i++) {
            tools.piecesToSend(CHAT, "обычная реплика " + i);
        }
        assertEquals(List.of("снова :3"), tools.piecesToSend(CHAT, "снова :3"));

        assertEquals(List.of("это - не лицо"), tools.piecesToSend(CHAT, "это — не лицо"),
                "срезка живёт там же, где замена тире: одна реплика — один проход");
    }

    @Test
    void aLineOfOneFaceIsDroppedAndTheRestOfTheMessageGoes() {
        TelegramTools tools = tools();
        assertEquals(List.of("первое >_<"), tools.piecesToSend(CHAT, "первое >_<"),
                "первое лицо в чате проходит");

        assertEquals(List.of("вторая половина"), tools.piecesToSend(CHAT, ":3\nвторая половина"),
                "обрезанная строка не должна превращаться в пустое сообщение");
        assertEquals(List.of(), tools.piecesToSend(CHAT, ":3"),
                "сообщению из одного лица отправлять нечего");
    }

    @Test
    void cooldownLengthComesFromTheSettings() {
        TelegramTools tools = tools();
        tools.behaviour(new Config.Behaviour(2000, false, true, false, true, 0.02, 1));

        assertEquals(List.of("первая >_<"), tools.piecesToSend(CHAT, "первая >_<"));
        assertEquals(List.of("вторая"), tools.piecesToSend(CHAT, "вторая >_<"));
        assertEquals(List.of("между ними"), tools.piecesToSend(CHAT, "между ними"));
        assertEquals(List.of("третья >_<"), tools.piecesToSend(CHAT, "третья >_<"));
    }

    /** Инструменты с настройками по умолчанию: тот же путь, что у живого бота до первой правки конфига. */
    private static TelegramTools tools() {
        return new TelegramTools(null, new TelegramActions.ReadOnly(), null,
                new Lockdown(Lockdown.Mode.OWNER_ONLY, 1L, true), null);
    }

    @Test
    void aCaptionIsGatedLikeAnyOtherMessage(@TempDir Path gallery) throws Exception {
        Sent sent = new Sent();
        TelegramTools tools = photoTools(gallery, sent);
        Tool send = tools.send(CHAT);

        assertEquals("Photo sent.", send.call(withPhoto("это чудо >_<")));
        assertEquals(List.of("это чудо >_<"), sent.captions, "первое лицо в чате проходит и в подпись");

        send.call(withPhoto("и снова чудо >_<"));
        assertEquals(List.of("это чудо >_<", "и снова чудо"), sent.captions,
                "следующее лицо срезано и из подписи");
    }

    @Test
    void aPhotoWhoseCaptionWasOnlyAFaceIsStillSent(@TempDir Path gallery) throws Exception {
        Sent sent = new Sent();
        Tool send = photoTools(gallery, sent).send(CHAT);
        send.call(withPhoto("лицо :3"));

        assertEquals("Photo sent.", send.call(withPhoto(":3")));
        assertEquals(List.of("лицо :3", ""), sent.captions,
                "снимку нечем подписаться — пустая подпись лучше неотправленного фото");
    }

    @Test
    void linesThatFollowTheCaptionAreGatedToo(@TempDir Path gallery) throws Exception {
        Sent sent = new Sent();
        Tool send = photoTools(gallery, sent).send(CHAT);
        send.call(withPhoto("первое :3"));

        assertEquals("Photo sent.", send.call(withPhoto("подпись\nи вот так >_<")));
        assertEquals(List.of("первое :3", "подпись"), sent.captions);
        assertEquals(List.of("и вот так"), sent.messages,
                "строки за подписью идут тем же проходом, что и обычная реплика");
    }

    private static JsonNode withPhoto(String text) {
        return JSON.createObjectNode().put("text", text).put("photo_filename", PHOTO);
    }

    /**
     * Инструменты, где снимок лежит в папке галереи, а всё ушедшее в чат
     * записывается: подпись отдельно, обычные сообщения отдельно.
     */
    private static TelegramTools photoTools(Path gallery, Sent sent) throws Exception {
        Files.writeString(gallery.resolve(PHOTO), "пиксели");
        TelegramActions actions = (TelegramActions) Proxy.newProxyInstance(
                TelegramActions.class.getClassLoader(), new Class<?>[] {TelegramActions.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "sendPhoto" -> sent.captions.add((String) args[2]);
                        case "sendMessage" -> {
                            sent.messages.add((String) args[1]);
                            return 1L;
                        }
                        default -> {
                            // «печатает…» и загрузка снимка: важно только то, что они не падают
                        }
                    }
                    return null;
                });
        TelegramTools tools = new TelegramTools(null, actions, null,
                new Lockdown(Lockdown.Mode.OWNER_ONLY, 1L, true), null);
        tools.gallery(gallery);
        return tools;
    }

    /** Что ушло в чат за вызовы инструмента. */
    private static final class Sent {
        private final List<String> captions = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
    }
}
