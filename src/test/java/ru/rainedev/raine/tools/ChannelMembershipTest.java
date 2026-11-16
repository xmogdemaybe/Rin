package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Подписка и отписка: только канал, только в круге, только по воле владельца.
 * Разрешение смотрит на инициатора хода, а не на открытого собеседника.
 */
class ChannelMembershipTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CHANNEL = -1001234L;

    private final List<String> calls = new ArrayList<>();
    private final TelegramActions actions = (TelegramActions) Proxy.newProxyInstance(
            TelegramActions.class.getClassLoader(), new Class<?>[] {TelegramActions.class},
            (proxy, method, args) -> {
                calls.add(method.getName());
                return null;
            });
    private final TelegramTools tools = new TelegramTools(null, actions, null,
            new Lockdown(Lockdown.Mode.NONE, 1L, true), null);

    private static TdApi.Chat chat(boolean isChannel) {
        TdApi.Chat found = new TdApi.Chat();
        found.id = CHANNEL;
        found.title = "канал";
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.supergroupId = 7L;
        type.isChannel = isChannel;
        found.type = type;
        return found;
    }

    private String join(boolean ownerTurn, boolean isChannel, boolean inCircle) throws Exception {
        tools.leads().ownerTurn(() -> ownerTurn);
        JsonNode arguments = JSON.createObjectNode().put("chat_id", CHANNEL);
        return tools.channelMembership(arguments, true, id -> chat(isChannel), c -> inCircle);
    }

    @Test
    void aGroupIsNeverSubscribedTo() throws Exception {
        String answer = join(true, false, true);

        assertTrue(answer.contains("not a channel"), answer);
        assertTrue(calls.isEmpty(), "флудилка не должна становиться подпиской: " + calls);
    }

    @Test
    void anOwnerTurnAllowsSubscribing() throws Exception {
        String answer = join(true, true, true);

        assertTrue(answer.contains("Subscribed"), answer);
        assertEquals(List.of("joinChat"), calls);
    }

    @Test
    void withoutTheOwnerNothingIsSubscribed() throws Exception {
        String answer = join(false, true, true);

        assertTrue(answer.contains("Only the owner"), answer);
        assertTrue(calls.isEmpty(), "чужая ссылка в группе подписку не даёт: " + calls);
    }

    @Test
    void aChannelOutsideTheCircleIsRefused() throws Exception {
        String answer = join(true, true, false);

        assertTrue(answer.contains("outside the allowed circle"), answer);
        assertTrue(calls.isEmpty());
    }

    /** Владелец просит отписаться словами, ссылки под рукой нет — это разрешено. */
    @Test
    void leavingWorksOnARecordedLeadWithoutAnyLink() throws Exception {
        tools.leads().ownerTurn(() -> true);
        tools.leads().vouch(CHANNEL);
        tools.leads().ownerTurn(() -> false);

        String answer = tools.channelMembership(JSON.createObjectNode().put("chat_id", CHANNEL),
                false, id -> chat(true), c -> true);

        assertTrue(answer.contains("Left the channel"), answer);
        assertEquals(List.of("leaveChat"), calls);
    }

    @Test
    void aMissingIdIsAskedFor() throws Exception {
        String answer = tools.channelMembership(JSON.createObjectNode(), true, id -> chat(true), c -> true);

        assertTrue(answer.contains("Provide the chat_id"), answer);
        assertTrue(calls.isEmpty());
    }

    @Test
    void bothToolsDisappearInSafeMode() {
        Toolbox safe = new Toolbox().actionsAllowed(false)
                .add(tools.joinChannel()).add(tools.leaveChannel());

        assertFalse(safe.names().contains("join_channel"), safe.names().toString());
        assertFalse(safe.names().contains("leave_channel"), safe.names().toString());
        assertTrue(tools.joinChannel().mutates(), "подписка — видимое действие");
        assertTrue(tools.leaveChannel().mutates(), "отписка — видимое действие");
    }
}