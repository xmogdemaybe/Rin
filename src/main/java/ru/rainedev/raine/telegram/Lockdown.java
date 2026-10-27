package ru.rainedev.raine.telegram;

import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.phone.ChatKind;

/**
 * С кем вообще позволено разговаривать.
 * <p>
 * Ограничение проверяется и на входящих, и при попытке открыть чат самой.
 * Фильтровать только уведомления недостаточно: найдя человека поиском, она
 * открыла бы чат по идентификатору и написала бы в обход запрета.
 */
public final class Lockdown {

    private static final Logger log = LoggerFactory.getLogger(Lockdown.class);

    public enum Mode {
        /** Разговаривает со всеми. */
        NONE,
        /** Только с теми, кто записан в контакты. */
        CONTACTS_ONLY,
        /** Только с владельцем. */
        OWNER_ONLY;

        public static Mode of(String value) {
            String written = value == null ? "" : value.trim().toLowerCase();
            return switch (written) {
                case "none", "public" -> NONE;
                case "contacts", "contacts_only" -> CONTACTS_ONLY;
                case "owner_only" -> OWNER_ONLY;
                // незнакомое слово сужает круг, а не расширяет: ошибиться тут
                // безопаснее вовне. Но опечатку показываем — написавший
                // «everyone» ждёт совсем не молчания
                default -> {
                    log.warn("Не знаю такого круга общения: «{}» — остаюсь только с владельцем", value);
                    yield OWNER_ONLY;
                }
            };
        }
    }

    private volatile Mode mode;
    private final long ownerId;
    private volatile boolean allowChannels;

    public Lockdown(Mode mode, long ownerId) {
        this(mode, ownerId, true);
    }

    /**
     * @param allowChannels читать ли каналы, когда круг общения сужен. Обычно да:
     *                      канал — это лента, а не собеседник. Но иногда хочется
     *                      тишины совсем
     */
    public Lockdown(Mode mode, long ownerId, boolean allowChannels) {
        this.mode = mode;
        this.ownerId = ownerId;
        this.allowChannels = allowChannels;
    }

    public Mode mode() {
        return mode;
    }

    /** Круг общения меняется на ходу: правка настроек не должна требовать перезапуска. */
    public void mode(Mode mode) {
        this.mode = mode;
    }

    public void allowChannels(boolean allowed) {
        this.allowChannels = allowed;
    }

    public boolean allowsChannels() {
        return allowChannels;
    }

    public boolean allows(TdApi.Chat chat, boolean isContact) {
        if (mode == Mode.NONE || chat == null) {
            return mode == Mode.NONE;
        }
        // Доступ к каналам разрешается отдельно даже в самом строгом режиме.
        if (ChatKind.of(chat) == ChatKind.CHANNEL) {
            return allowChannels;
        }
        if (chat.type instanceof TdApi.ChatTypePrivate priv && priv.userId == ownerId) {
            return true;
        }
        if (mode != Mode.CONTACTS_ONLY) {
            return false;
        }
        // групповой чат, в котором она состоит, — это круг знакомых по определению:
        // её туда позвали, и переписка там общая, а не личная
        return ChatKind.of(chat) == ChatKind.GROUP || isContact;
    }

    /** Личный чат с владельцем — его нельзя ни закрыть, ни заблокировать. */
    public boolean isOwner(long chatId) {
        return chatId == ownerId;
    }

    public boolean allowsSender(long senderId, boolean isContact) {
        return switch (mode) {
            case NONE -> true;
            case CONTACTS_ONLY -> senderId == ownerId || isContact;
            case OWNER_ONLY -> senderId == ownerId;
        };
    }
}
