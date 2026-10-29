package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Превращает сообщение Telegram в разметку, которую видит модель.
 * <p>
 * Это часть имитации телефона: модель не работает с объектами Telegram,
 * она «читает переписку» в виде тегов {@code <message sender="..">}.
 */
public final class MessageFormatter {

    /** Разрешает имя по идентификатору пользователя или чата. */
    @FunctionalInterface
    public interface NameResolver {
        String nameOf(long id);
    }

    /** Достаёт сообщение, на которое отвечают. Пусто, если удалено или недоступно. */
    @FunctionalInterface
    public interface ReplyLookup {
        Optional<TdApi.Message> find(long chatId, long messageId);

        ReplyLookup NONE = (chatId, messageId) -> Optional.empty();
    }

    /**
     * Ссылки на чужие посты. {@code peek} только читает то, что уже раскрыли:
     * разметка сама к Telegram не ходит. {@code lookAhead} раскрывает адреса всего
     * экрана разом — до того, как экран начнёт собираться.
     */
    public interface LinkLookup {
        Optional<TdApi.MessageLinkInfo> peek(String url);

        default void lookAhead(List<TdApi.Message> messages) {}

        LinkLookup NONE = url -> Optional.empty();
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NameResolver names;
    private final ReplyLookup replies;
    private final MediaDescriber media;
    private final MessageEdits edits;
    private final LinkLookup links;

    public MessageFormatter(NameResolver names, ReplyLookup replies) {
        this(names, replies, MediaDescriber.NONE);
    }

    public MessageFormatter(NameResolver names, ReplyLookup replies, MediaDescriber media) {
        this(names, replies, media, MessageEdits.NONE);
    }

    public MessageFormatter(NameResolver names, ReplyLookup replies, MediaDescriber media,
                            MessageEdits edits) {
        this(names, replies, media, edits, LinkLookup.NONE);
    }

    public MessageFormatter(NameResolver names, ReplyLookup replies, MediaDescriber media,
                            MessageEdits edits, LinkLookup links) {
        this.names = names;
        this.replies = replies;
        this.media = media;
        this.edits = edits;
        this.links = links;
    }

    /**
     * Состояние чата, нужное для разметки.
     *
     * @param myId                    идентификатор самой Raine — её сообщения не помечаются непрочитанными
     * @param lastReadInboxMessageId  граница прочитанного
     */
    public record ChatView(long myId, long lastReadInboxMessageId) {}

    MediaDescriber media() {
        return media;
    }

    LinkLookup links() {
        return links;
    }

    public String format(TdApi.Message message, ChatView view) {
        return format(message, view, "message", "");
    }

    /**
     * @param isTarget пометка «вот это искали» ставится атрибутом в самом теге,
     *                 а не строкой сверху: так она не путается с содержимым
     *                 соседнего сообщения
     */
    public String format(TdApi.Message message, ChatView view, boolean isTarget) {
        return format(message, view, "message", isTarget ? " target" : "");
    }

    /**
     * То же, но без разглядывания вложений: у старых сообщений в истории видно
     * только их тип. Разобрать десяток картинок стоит минуту, а разговор идёт
     * про последние из них — за остальными всегда можно вернуться поиском.
     */
    public String formatBriefly(TdApi.Message message, ChatView view) {
        return new MessageFormatter(names, replies, MediaDescriber.NONE, edits, LinkLookup.NONE)
                .format(message, view);
    }

    private String format(TdApi.Message message, ChatView view, String tag) {
        return format(message, view, tag, "");
    }

    /**
     * @param extra готовые атрибуты этого блока — те, что знает только вызывающий
     */
    private String format(TdApi.Message message, ChatView view, String tag, String extra) {
        // самостоятельная реплика переписки, а не приписка к ней: цитата в чужом
        // ответе и пост за ссылкой не раскручиваются дальше
        boolean turn = "message".equals(tag);
        StringBuilder attributes = new StringBuilder(tag).append(extra)
                .append(" message_id=\"").append(message.id).append('"')
                .append(" date=\"").append(DATE.format(Instant.ofEpochSecond(message.date))).append('"');

        long senderId = senderId(message.senderId);
        // граница прочитанного относится к открытому чату: чужой пост за ссылкой
        // ничего не говорит о том, дочитала ли она сюда
        if (!"link".equals(tag) && senderId != view.myId() && message.id > view.lastReadInboxMessageId()) {
            attributes.append(" unread");
        }

        if (message.authorSignature != null && !message.authorSignature.isEmpty()) {
            attributes.append(" author_signature=\"").append(Markup.attribute(message.authorSignature)).append('"');
        }

        appendSender(attributes, message, senderId);
        appendReactions(attributes, message);

        StringBuilder body = new StringBuilder();
        // вложенный reply_to разворачиваем только на один уровень, иначе цепочка ответов
        // раздувает контекст без пользы
        if (turn && message.replyTo instanceof TdApi.MessageReplyToMessage reply) {
            long chatId = reply.chatId == 0 ? message.chatId : reply.chatId;
            body.append(replies.find(chatId, reply.messageId)
                    .map(original -> format(original, view, "reply_to"))
                    .orElse("<reply_to>Удалённое сообщение</reply_to>\n"));
        }
        String content = ControlMarkers.sanitize(content(message.content));
        body.append(content);
        appendEdits(body, attributes, message, content, turn);
        appendLinks(body, message, view, turn);

        // описание встаёт под вложением: сначала «прислал фото», потом что на нём
        // расшифровка голосового и описание картинки — тоже чужой текст,
        // и служебные маркеры в них так же недопустимы
        String described = ControlMarkers.sanitize(media.describe(message));
        if (!described.isBlank()) {
            body.append('\n').append(described.strip());
        }

        return "<%s>\n%s\n</%s>\n".formatted(attributes, body.toString().strip(), tag);
    }

    /**
     * Что собеседник поменял в уже прочитанном сообщении.
     * <p>
     * Свои правки не показываем: это механика опечаток, а не слова собеседника.
     * Цитата и пост за ссылкой размечаются без правок: дифф показывают один раз,
     * и отдавать его цитате значит не дать прочитать само сообщение.
     * <p>
     * Прежние слова заново через {@code Markup.body} не гоним: свободный текст уже
     * размечен в {@code MessageContent}, а служебные теги Telegram — превью ссылки
     * или звонок — внутри правки как раз должны остаться целыми.
     */
    private void appendEdits(StringBuilder body, StringBuilder attributes,
                             TdApi.Message message, String content, boolean turn) {
        if (!turn || message.isOutgoing) {
            return;
        }
        Optional<String> previous = edits.previous(message.chatId, message.id);
        edits.remember(message.chatId, message.id, content);
        if (previous.isPresent() && !previous.get().equals(content)) {
            body.append("\n<edited>\n<was>").append(previous.get())
                    .append("</was>\n<now>").append(content).append("</now>\n</edited>");
        } else if (message.editDate != 0) {
            // прежних слов не знать (правили до первого чтения) либо перемены с
            // прошлого показа нет: дифф показывают один раз, дальше — только отметка
            attributes.append(" edited=\"true\"");
        }
    }

    /**
     * Чужой пост по ссылке вкладывается сюда же: адрес без текста говорит только
     * «там что-то написано», а своей тулзы открыть ссылку у неё нет.
     * <p>
     * Вложенный блок собирается выбросным форматтером — без зрения, правок и
     * ссылок внутри ссылок. Иначе канал за одной ссылкой тянет за собой десяток
     * картинок и собственную ленту пересказов.
     */
    private void appendLinks(StringBuilder body, TdApi.Message message, ChatView view, boolean turn) {
        if (!turn) {
            return;
        }
        for (String url : TmeLink.in(message, TmeLink.PER_MESSAGE)) {
            TdApi.MessageLinkInfo info = links.peek(url).orElse(null);
            if (info == null || info.message == null) {
                continue;   // адрес ведёт в чат целиком либо закрыт от этого аккаунта
            }
            TdApi.Message post = info.message;
            String chat = Markup.attribute(names.nameOf(post.chatId));
            String extra = " url=\"" + Markup.attribute(url) + '"'
                    + (chat.isBlank() ? "" : " chat=\"" + chat + '"')
                    + (info.forAlbum || post.mediaAlbumId != 0 ? " album=\"true\"" : "");
            body.append('\n').append(new MessageFormatter(names, replies, MediaDescriber.NONE,
                    MessageEdits.NONE, LinkLookup.NONE).format(post, view, "link", extra));
        }
    }

    /**
     * Реакции показываются как в обычном клиенте: до трёх — с именами тех,
     * кто поставил, дальше — числом. Кто именно отреагировал, важнее счётчика:
     * по этому видно отношение конкретного человека.
     */
    private void appendReactions(StringBuilder attributes, TdApi.Message message) {
        if (message.interactionInfo == null || message.interactionInfo.reactions == null) {
            return;
        }
        StringBuilder reactions = new StringBuilder();
        for (TdApi.MessageReaction reaction : message.interactionInfo.reactions.reactions) {
            if (!(reaction.type instanceof TdApi.ReactionTypeEmoji emoji)) {
                continue;   // платные и кастомные ничего не говорят об отношении
            }
            if (!reactions.isEmpty()) {
                reactions.append(';');
            }
            reactions.append('(').append(emoji.emoji);
            if (reaction.totalCount > 3 || reaction.recentSenderIds == null) {
                reactions.append(' ').append(reaction.totalCount).append(')');
                continue;
            }
            reactions.append(" by ");
            for (int i = 0; i < reaction.recentSenderIds.length; i++) {
                if (i > 0) {
                    reactions.append(", ");
                }
                reactions.append(names.nameOf(senderId(reaction.recentSenderIds[i])));
            }
            reactions.append(')');
        }
        if (!reactions.isEmpty()) {
            attributes.append(" reactions=\"").append(Markup.attribute(reactions.toString())).append('"');
        }
    }

    /**
     * Для Telegram отправитель пересланного — тот, кто переслал. Модель из-за этого
     * приписывает ему авторство, поэтому поля меняем местами: {@code sender} —
     * настоящий автор, {@code forwarded_by} — тот, кто поделился.
     */
    private void appendSender(StringBuilder attributes, TdApi.Message message, long senderId) {
        String senderName = Markup.attribute(names.nameOf(senderId));

        if (message.forwardInfo != null) {
            long originId = originId(message.forwardInfo.origin);
            if (originId != 0) {
                attributes.append(" sender=\"").append(Markup.attribute(names.nameOf(originId))).append('"');
            }
            if (!senderName.isBlank()) {
                attributes.append(" forwarded_by=\"").append(senderName).append('"');
            }
            return;
        }

        if (!senderName.isBlank()) {
            attributes.append(" sender=\"").append(senderName).append('"');
        }
    }

    private static long senderId(TdApi.MessageSender sender) {
        return switch (sender) {
            case TdApi.MessageSenderUser user -> user.userId;
            case TdApi.MessageSenderChat chat -> chat.chatId;
            case null, default -> 0L;
        };
    }

    private static long originId(TdApi.MessageOrigin origin) {
        return switch (origin) {
            case TdApi.MessageOriginChannel channel -> channel.chatId;
            case TdApi.MessageOriginUser user -> user.senderUserId;
            case TdApi.MessageOriginChat chat -> chat.senderChatId;
            case null, default -> 0L;
        };
    }

    /** Разбор содержимого вынесен отдельно: типов сообщений в Telegram под сотню. */
    public static String contentOf(TdApi.MessageContent content) {
        return MessageContent.describe(content);
    }

    private static String content(TdApi.MessageContent content) {
        return MessageContent.describe(content);
    }
}
