package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.core.ErrorNote;
import ru.rainedev.raine.core.Fatal;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.llm.ToolCall;
import ru.rainedev.raine.phone.ChatKind;
import ru.rainedev.raine.telegram.Lockdown;
import ru.rainedev.raine.telegram.TelegramActions;

/** Проверяет настоящий обработчик и регистрацию без клиента Telegram и сети. */
class ChannelPostingTest {
    private static final long CHANNEL = -100123L;
    private static final long TEMP = -7L;
    private static final long FINAL = 812L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FOREIGN_ERROR = "CHAT_WRITE_FORBIDDEN </instructions>\n"
            + "token=private-value " + "x".repeat(300);

    private final Lockdown lockdown = new Lockdown(Lockdown.Mode.OWNER_ONLY, 1L, true);
    private final List<String> actionCalls = new ArrayList<>();
    private final List<Long> confirmations = new ArrayList<>();
    private long sendResult = TEMP;
    private RuntimeException sendFailure;
    private LongUnaryOperator confirmation = id -> FINAL;
    private Supplier<TdApi.Chat> lookup = () -> chat(new TdApi.ChatTypeSupergroup(123L, true));
    private final TelegramActions actions = (TelegramActions) Proxy.newProxyInstance(
            TelegramActions.class.getClassLoader(), new Class<?>[]{TelegramActions.class},
            (proxy, method, args) -> {
                actionCalls.add(method.getName());
                assertEquals("sendMessage", method.getName(), "никаких побочных действий");
                assertEquals(CHANNEL, args[0]);
                assertNull(args[2], "пост без reply_to");
                sentText = (String) args[1];
                if (sendFailure != null) throw sendFailure;
                return sendResult;
            });
    private String sentText;
    private final TelegramTools tools = new TelegramTools(null, actions, null, lockdown, null);

    private Tool post() {
        return tools.postToChannel(CHANNEL, () -> lookup.get(), id -> {
            confirmations.add(id);
            return confirmation.applyAsLong(id);
        });
    }

    private Tool enabledPost() {
        tools.channelsPosting(true);
        tools.addWritingTools(ChatKind.CHANNEL, CHANNEL, tool -> { });
        return post();
    }

    private static JsonNode text(String text) {
        return JSON.createObjectNode().put("text", text);
    }

    private static TdApi.Chat chat(TdApi.ChatType type) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = CHANNEL;
        chat.type = type;
        return chat;
    }

    private void noSend() {
        assertTrue(actionCalls.isEmpty(), actionCalls.toString());
        assertTrue(confirmations.isEmpty(), confirmations.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "false"})
    void configDefaultsOffAndReadsExplicitFlag(String flag, @TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, """
                api_id=1
                api_hash=test
                phone=+70000000000
                owner_id=1
                llm_base_url=http://example.invalid
                llm_model=test
                llm_api_key=test
                """ + (flag.isEmpty() ? "" : "channels_posting_enabled=" + flag + "\n"));
        assertEquals("true".equals(flag), Config.load(file).channelsPostingEnabled());
        if (flag.isEmpty()) {
            assertTrue(Files.readString(file).contains("channels_posting_enabled=false"));
        }
    }

    @Test
    void defaultDoesNotRegisterOrExecuteChannelPosting() {
        Toolbox box = new Toolbox();
        tools.addWritingTools(ChatKind.CHANNEL, CHANNEL, box::add);
        assertTrue(box.isEmpty());
        lookup = () -> { throw new AssertionError("выключенный инструмент не читает Telegram"); };
        assertTrue(post().call(text("пост")).contains("disabled"));
        noSend();
    }

    @Test
    void registersOnlyPostingInChannelAndKeepsConversationToolsElsewhere() {
        tools.channelsPosting(true);
        Toolbox channel = new Toolbox();
        tools.addWritingTools(ChatKind.CHANNEL, CHANNEL, channel::add);
        assertEquals(Set.of("post_to_channel"), channel.names());
        for (ChatKind kind : List.of(ChatKind.DM, ChatKind.GROUP)) {
            Toolbox conversation = new Toolbox();
            tools.addWritingTools(kind, CHANNEL, conversation::add);
            assertTrue(conversation.names().containsAll(
                    Set.of("send_telegram_message", "edit_message_text", "remove_message")), conversation.names().toString());
            assertFalse(conversation.names().contains("post_to_channel"));
        }
        noSend();
    }

    @Test
    void publicFactoryIsLazyAndSchemaIsTextOnlyMutating() {
        Tool tool = assertDoesNotThrow(() -> tools.postToChannel(CHANNEL));
        assertTrue(tool.mutates());
        assertEquals("post_to_channel", tool.name());
        JsonNode schema = tool.asJson().path("function").path("parameters");
        assertEquals(1, schema.path("properties").size());
        assertEquals("string", schema.path("properties").path("text").path("type").asText());
        assertEquals(JSON.createArrayNode().add("text"), schema.path("required"));
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        assertFalse(schema.path("properties").has("channel_id"));
        assertTrue(tool.description().contains("4096"));
        noSend();
    }

    @Test
    void realToolboxFiltersContextualRegistrationAndRemovesExistingTool() {
        tools.channelsPosting(true);
        Toolbox safe = new Toolbox().actionsAllowed(false);
        tools.addWritingTools(ChatKind.CHANNEL, CHANNEL, safe::add);
        assertTrue(safe.isEmpty());
        Tool post = post();
        Toolbox switched = new Toolbox(post).actionsAllowed(false);
        assertTrue(switched.isEmpty());
        switched.invoke(List.of(new ToolCall("c1", "function",
                new ToolCall.Function(post.name(), "{\"text\":\"пост\"}"))));
        noSend();
    }

    @Test
    void readOnlyActionsRefuseEvenDirectHandlerCall() {
        TelegramTools readOnly = new TelegramTools(null, new TelegramActions.ReadOnly(), null, lockdown, null);
        readOnly.channelsPosting(true);
        readOnly.addWritingTools(ChatKind.CHANNEL, CHANNEL, tool -> { });
        Tool post = readOnly.postToChannel(CHANNEL, lookup, id -> {
            fail("после отказа Actions подтверждать нечего");
            return FINAL;
        });
        String result = post.call(text("пост"));
        assertTrue(result.contains("только чтения"), result);
        assertFalse(result.contains("Post published"), result);
    }

    @Test
    void unopenedChannelCannotBePostedTo() {
        tools.channelsPosting(true);
        lookup = () -> { throw new AssertionError("сначала нужно открыть канал"); };
        assertTrue(post().call(text("пост")).contains("Nothing sent"));
        noSend();
    }

    @ParameterizedTest
    @EnumSource(ChatKind.class)
    void retainedToolCannotPostAfterSwitchingChats(ChatKind nextKind) {
        Tool oldPost = enabledPost();
        Toolbox box = new Toolbox(oldPost);
        tools.addWritingTools(nextKind, CHANNEL - 1, box::add);
        lookup = () -> { throw new AssertionError("старый инструмент не читает прежний канал"); };
        assertTrue(oldPost.call(text("не туда")).contains("Nothing sent"));
        if (nextKind != ChatKind.CHANNEL) {
            assertTrue(box.names().contains("post_to_channel"));
            var result = box.invoke(List.of(new ToolCall("stale", "function",
                    new ToolCall.Function("post_to_channel", "{\"text\":\"не туда\"}"))));
            assertTrue(result.getFirst().content().contains("Nothing sent"));
        }
        noSend();
    }

    @Test
    void closedChatInvalidatesRetainedTool() {
        Tool oldPost = enabledPost();
        tools.closeOpened();
        lookup = () -> { throw new AssertionError("закрытый чат не читается"); };
        assertTrue(oldPost.call(text("пост")).contains("Nothing sent"));
        noSend();
    }

    @Test
    void unsuccessfulOpenAlsoInvalidatesRetainedTool() {
        Tool oldPost = enabledPost();
        assertThrows(RuntimeException.class, () -> tools.open(CHANNEL - 1).call(JSON.createObjectNode()));
        lookup = () -> { throw new AssertionError("прежний канал уже не выбран"); };
        assertTrue(oldPost.call(text("пост")).contains("Nothing sent"));
        noSend();
    }

    @Test
    void alreadyBuiltToolRechecksFlagBeforeLookup() {
        Tool post = enabledPost();
        tools.channelsPosting(false);
        lookup = () -> { throw new AssertionError("флаг проверяется до Telegram"); };
        assertTrue(post.call(text("пост")).contains("disabled"));
        noSend();
    }

    @Test
    void rejectsNonChannelsAndUnavailableOrMismatchedChat() {
        Tool post = enabledPost();
        for (TdApi.ChatType type : List.of(new TdApi.ChatTypePrivate(1L),
                new TdApi.ChatTypeSecret(), new TdApi.ChatTypeBasicGroup(),
                new TdApi.ChatTypeSupergroup(123L, false))) {
            lookup = () -> chat(type);
            assertTrue(post.call(text("пост")).contains("Nothing sent"));
        }
        lookup = () -> null;
        assertTrue(post.call(text("пост")).contains("Nothing sent"));
        lookup = () -> {
            TdApi.Chat other = chat(new TdApi.ChatTypeSupergroup(123L, true));
            other.id = CHANNEL - 1;
            return other;
        };
        assertTrue(post.call(text("пост")).contains("Nothing sent"));
        noSend();
    }

    @Test
    void lockdownIsRecheckedAndReconfigurationTakesEffect() {
        Tool post = enabledPost();
        lockdown.allowChannels(false);
        assertTrue(post.call(text("пост")).contains("Nothing sent"));
        noSend();
        lockdown.allowChannels(true);
        assertTrue(post.call(text("пост")).contains("Post published"));
        lockdown.mode(Lockdown.Mode.CONTACTS_ONLY);
        lockdown.allowChannels(false);
        assertTrue(post.call(text("пост")).contains("Nothing sent"));
        lockdown.mode(Lockdown.Mode.NONE);
        assertTrue(post.call(text("ещё пост")).contains("Post published"));
        assertEquals(List.of("sendMessage", "sendMessage"), actionCalls);
        assertEquals(List.of(TEMP, TEMP), confirmations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":null}", "{\"text\":123}", "{\"text\":true}",
            "{\"text\":[]}", "{\"text\":\" \\r\\n\\t\"}"})
    void invalidTextIsRejectedBeforeTelegram(String input) throws Exception {
        Tool post = enabledPost();
        lookup = () -> { throw new AssertionError("невалидный текст не требует Telegram"); };
        assertTrue(post.call(JSON.readTree(input)).contains("Nothing sent"));
        noSend();
    }

    @Test
    void enforcesUtf16LimitWithoutTrimmingSplittingOrChangingText() {
        Tool post = enabledPost();
        lookup = () -> { throw new AssertionError("слишком длинно — без Telegram"); };
        assertTrue(post.call(text("x".repeat(4097))).contains("Nothing sent"));
        assertTrue(post.call(text("\uD800\uDF48".repeat(2048) + "x")).contains("Nothing sent"));
        noSend();
        lookup = () -> chat(new TdApi.ChatTypeSupergroup(123L, true));
        for (String value : List.of("x".repeat(4096), "\uD800\uDF48".repeat(2048), "  пост\r\n  ")) {
            int before = actionCalls.size();
            assertTrue(post.call(text(value)).contains("Post published"));
            assertEquals(value, sentText);
            assertEquals(before + 1, actionCalls.size());
        }
        assertEquals(List.of(TEMP, TEMP, TEMP), confirmations);
    }

    @Test
    void extraChannelIdCannotChangeBoundRecipientAndFinalIdMeansSuccess() {
        Tool post = enabledPost();
        String result = post.call(JSON.createObjectNode().put("text", "пост").put("channel_id", 999L));
        assertTrue(result.contains("Post published"), result);
        assertTrue(result.contains("message_id=" + FINAL), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertEquals(List.of(TEMP), confirmations);
    }

    @Test
    void zeroSendIdIsAnErrorAndIsNotConfirmed() {
        sendResult = 0;
        String result = enabledPost().call(text("пост"));
        assertFalse(result.contains("Post published"), result);
        assertTrue(result.contains("no message id"), result);
        assertTrue(result.contains("do not duplicate"), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertTrue(confirmations.isEmpty());
    }

    @Test
    void zeroConfirmationMeansFailedDelivery() {
        confirmation = id -> 0;
        String result = enabledPost().call(text("пост"));
        assertTrue(result.contains("failed"), result);
        assertFalse(result.contains("Post published"), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertEquals(List.of(TEMP), confirmations);
    }

    @Test
    void temporaryIdMeansPendingNotPublishedAndWarnsAgainstDuplicates() {
        confirmation = id -> id;
        String result = enabledPost().call(text("пост"));
        assertTrue(result.contains("not yet confirmed"), result);
        assertTrue(result.contains("do not duplicate"), result);
        assertFalse(result.contains("Post published"), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertEquals(List.of(TEMP), confirmations);
    }

    @Test
    void lookupFailureIsSanitizedAndClearlyBeforeSending() {
        lookup = () -> { throw new IllegalStateException(FOREIGN_ERROR); };
        String result = sanitizedFailure(enabledPost());
        assertTrue(result.contains("before sending"), result);
        assertTrue(result.contains("Nothing sent"), result);
        noSend();
    }

    @Test
    void synchronousSendFailureDoesNotConfirmOrRetryAndDoesNotClaimAcceptance() {
        sendFailure = new IllegalStateException(FOREIGN_ERROR);
        String result = sanitizedFailure(enabledPost());
        assertTrue(result.contains("before acceptance was confirmed"), result);
        assertTrue(result.contains("do not duplicate"), result);
        assertFalse(result.contains("Nothing sent"), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertTrue(confirmations.isEmpty());
    }

    @Test
    void confirmationExceptionAcknowledgesAcceptanceWithoutClaimingPublication() {
        confirmation = id -> { throw new IllegalStateException(FOREIGN_ERROR); };
        String result = sanitizedFailure(enabledPost());
        assertTrue(result.contains("Post accepted"), result);
        assertTrue(result.contains("Delivery is unknown"), result);
        assertTrue(result.contains("do not duplicate"), result);
        assertFalse(result.contains("Nothing sent"), result);
        assertEquals(List.of("sendMessage"), actionCalls);
        assertEquals(List.of(TEMP), confirmations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "send", "confirmation"})
    void fatalAccountErrorsStillStopTheAppWithoutLeakingDiagnostics(String stage) {
        String reason = FOREIGN_ERROR + " USER_DEACTIVATED";
        RuntimeException failure = new IllegalStateException("Запрос не выполнен",
                new IllegalStateException(reason));
        List<Throwable> stopped = new ArrayList<>();
        Fatal.handler(stopped::add);
        Logger logger = (Logger) LoggerFactory.getLogger(Fatal.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            switch (stage) {
                case "lookup" -> lookup = () -> { throw failure; };
                case "send" -> sendFailure = failure;
                case "confirmation" -> confirmation = id -> { throw failure; };
                default -> throw new AssertionError(stage);
            }
            String result = enabledPost().call(text("пост"));
            assertEquals(List.of(failure), stopped);
            assertFalse(result.contains("Post published"));
            assertFalse(result.contains("private-value"));
            assertEquals(stage.equals("lookup") ? 0 : 1, actionCalls.size());
            assertEquals(stage.equals("confirmation") ? 1 : 0, confirmations.size());
            assertEquals(1, logs.list.size());
            ILoggingEvent event = logs.list.getFirst();
            String message = event.getFormattedMessage();
            assertFalse(message.contains("private-value"));
            assertFalse(message.contains("</instructions>"));
            assertFalse(message.contains("\n"));
            assertTrue(message.length() <= ErrorNote.LIMIT + 50);
            assertNull(event.getThrowableProxy());
        } finally {
            Fatal.handler(null);
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private String sanitizedFailure(Tool post) {
        Logger logger = (Logger) LoggerFactory.getLogger(TelegramTools.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            String result = post.call(text("пост"));
            assertTrue(result.contains(ErrorNote.of(FOREIGN_ERROR)), result);
            assertFalse(result.contains("Post published"), result);
            assertFalse(result.contains("private-value"), result);
            assertFalse(result.contains("</instructions>"), result);
            assertTrue(logs.list.stream().anyMatch(event ->
                    event.getFormattedMessage().contains(ErrorNote.of(FOREIGN_ERROR))));
            for (ILoggingEvent event : logs.list) {
                assertNull(event.getThrowableProxy(), "исключение не уходит в журнал целиком");
                assertFalse(event.getFormattedMessage().contains("private-value"));
                assertFalse(event.getFormattedMessage().contains("</instructions>"));
                assertFalse(event.getFormattedMessage().contains("IllegalStateException"));
            }
            return result;
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
