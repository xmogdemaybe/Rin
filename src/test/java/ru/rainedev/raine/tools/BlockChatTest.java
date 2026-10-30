package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Закрытие двери против того, кто не понимает отказа. Разблокировки в боте нет,
 * поэтому здесь закреплено, кому бан недоступен и что именно он накрывает:
 * отказ владельцу, привязка к открытому чату и исчезновение тулзы в безопасном
 * режиме — всё это проверяется без клиента Telegram.
 */
class BlockChatTest {

    private static final long OWNER = 1L;
    private static final long STRANGER = 999L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Lockdown lockdown = new Lockdown(Lockdown.Mode.OWNER_ONLY, OWNER, true);
    private final List<Long> blocked = new ArrayList<>();
    private final TelegramActions actions = (TelegramActions) Proxy.newProxyInstance(
            TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
            (proxy, method, args) -> {
                assertEquals("blockAndLeave", method.getName(), "бан не должен трогать ничего другого");
                blocked.add((Long) args[0]);
                return null;
            });
    private final TelegramTools tools = new TelegramTools(null, actions, null, lockdown, null);

    private static ObjectNode noArguments() {
        return JSON.createObjectNode();
    }

    @Test
    void theOwnerCannotBeBlockedAndNothingReachesTelegram() {
        String answer = tools.blockChat(OWNER).call(noArguments());

        assertTrue(answer.contains("cannot block"), "отказ должен быть явным: " + answer);
        assertTrue(blocked.isEmpty(), "до Telegram дело не дошло: " + blocked);
    }

    @Test
    void aStrangerIsBlockedOnce() {
        assertEquals("Chat blocked.", tools.blockChat(STRANGER).call(noArguments()));
        assertEquals(List.of(STRANGER), blocked);
    }

    @Test
    void anArgumentCannotRetargetTheBan() {
        // цель привязана к открытому чату: номер чата в аргументах не выбирает,
        // кого блокировать — иначе чужой текст диктовал бы, кому закрывать дверь
        tools.blockChat(STRANGER).call(noArguments().put("chat_id", OWNER));

        assertEquals(List.of(STRANGER), blocked, "заблокирован остался тот, чат которого открыт");
    }

    @Test
    void banIsAnActionSoSafeModeNeverOffersIt() {
        Tool ban = tools.blockChat(STRANGER);

        assertTrue(ban.mutates(), "блокировка меняет Telegram — значит, это действие");
        Toolbox safe = new Toolbox().actionsAllowed(false).add(ban);
        assertFalse(safe.names().contains("remove_and_ban_chat"),
                "в безопасном режиме тулзы в наборе быть не должно: " + safe.names());
    }

    @Test
    void readOnlyLayerRefusesInsteadOfQuietlyDoingNothing() {
        TelegramTools guarded = new TelegramTools(null, new TelegramActions.ReadOnly(), null, lockdown, null);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> guarded.blockChat(STRANGER).call(noArguments()));

        assertTrue(String.valueOf(failure.getMessage()).contains("Режим только чтения"),
                "по отказу должно быть видно, чей это режим: " + failure.getMessage());
    }
}
