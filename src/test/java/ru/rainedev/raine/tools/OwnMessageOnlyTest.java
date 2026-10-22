package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import it.tdlight.jni.TdApi;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Numbers;

/**
 * Удаление и правка обещают «своё сообщение» — но проверяли только то, что
 * номер существует в этом чате. В группе, где она админ, Telegram позволяет
 * убирать и чужие реплики, поэтому одного «удали всё, что он писал» хватило бы
 * вычистить переписку вокруг неё. Плюс список номеров в одном вызове нигде не
 * был ограничен, а разбор спотыкался о `Long.parseLong` и молча терял
 * идентификаторы, которые модель пишет в научной записи.
 */
class OwnMessageOnlyTest {

    private static final long HER = 42L;

    private static TdApi.Message from(long senderId) {
        TdApi.Message message = new TdApi.Message();
        message.id = 100;
        message.senderId = new TdApi.MessageSenderUser(senderId);
        return message;
    }

    private static JsonNode json(String arguments) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void herOwnMessageIsHers() {
        assertTrue(TelegramTools.isHers(from(HER), HER));
    }

    @Test
    void somebodysElseMessageIsNot() {
        assertFalse(TelegramTools.isHers(from(999L), HER),
                "чужая реплика в том же чате — не её вещь и удалять её нечем");
    }

    @Test
    void aServiceMessageHasNoAuthorToMatch() {
        // senderId служебного сообщения — пустой пользователь; подставлять под
        // неё ноль значит выдать системную строку за её собственную
        assertFalse(TelegramTools.isHers(from(0L), 0L));
        assertFalse(TelegramTools.isHers(from(0L), HER));
    }

    @Test
    void idsComeOutOfOneFieldInAnyShapeTheModelPicks() {
        assertEquals(List.of(5L, 6L, 7L), Numbers.idsAt(json("""
                {"message_id": "5, 6, 7"}"""), "message_id"));
        assertEquals(List.of(5L, 6L), Numbers.idsAt(json("""
                {"message_id": "5 6"}"""), "message_id"));
        assertEquals(List.of(5L, 6L), Numbers.idsAt(json("""
                {"message_id": [5, 6]}"""), "message_id"));
    }

    @Test
    void garbageBetweenTheIdsIsSkippedNotFatal() {
        assertEquals(List.of(5L, 7L), Numbers.idsAt(json("""
                {"message_id": "5, и ещё, 7"}"""), "message_id"));
        assertEquals(List.of(), Numbers.idsAt(json("""
                {"message_id": ""}"""), "message_id"));
        assertEquals(List.of(), Numbers.idsAt(json("""
                {"other": "1"}"""), "message_id"));
    }

    @Test
    void anIdWrittenInScientificNotationIsStillAnId() {
        // старый разбор спотыкался о такую запись и молча терял номер: удаление
        // выглядело успехом, а сообщение оставалось на месте
        assertEquals(List.of(757600000L), Numbers.idsAt(json("""
                {"message_id": "7.576e+08"}"""), "message_id"));
    }

    @Test
    void aWallOfIdsIsRefusedButTenPass() {
        List<Long> ten = java.util.stream.LongStream.rangeClosed(1, 10).boxed().toList();
        List<Long> eleven = java.util.stream.LongStream.rangeClosed(1, 11).boxed().toList();

        assertFalse(TelegramTools.tooMany(ten));
        assertTrue(TelegramTools.tooMany(eleven), "одиннадцать — это уже зачистка, а не «*опечатка»");
    }
}
