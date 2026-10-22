package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/**
 * Превращает содержимое сообщения в слова.
 * <p>
 * Тип важен сам по себе: «прислал фото» — уже событие, даже без подписи.
 * Подарок, пропущенный звонок, скриншот переписки — тем более: живой человек
 * на такое отвечает, а нераспознанное он бы просто не заметил.
 */
public final class MessageContent {

    private MessageContent() {}

    public static String describe(TdApi.MessageContent content) {
        // свободный текст сообщений проходит через Markup: он приходит от
        // посторонних людей и не должен закрывать блоки переписки
        return switch (content) {
            // то, из чего состоит обычная переписка
            case TdApi.MessageText text -> text.linkPreview == null
                    ? Markup.body(text.text.text)
                    // в превью часто весь смысл: без него остаётся голый адрес
                    : Markup.body(text.text.text) + "\n" + linkPreview(text.linkPreview);
            case TdApi.MessagePhoto photo -> withCaption("[фото]", photo.caption);
            case TdApi.MessageVideo video -> withCaption("[видео]", video.caption);
            case TdApi.MessageAnimation animation -> withCaption("[гифка]", animation.caption);
            case TdApi.MessageVoiceNote voice -> withCaption("[голосовое]", voice.caption);
            case TdApi.MessageVideoNote ignored -> "[кружок]";
            // id обязателен: без него стикер не забрать себе и не отправить потом,
            // а взять его больше неоткуда — эмодзи стикер не опознаёт
            case TdApi.MessageSticker sticker ->
                    "[стикер sticker_id=%d] %s".formatted(sticker.sticker.id, Markup.body(sticker.sticker.emoji));
            case TdApi.MessageAudio audio ->
                    withCaption("[аудио] " + Markup.body(audio.audio.title), audio.caption);
            case TdApi.MessageDocument document -> withCaption(
                    "[файл] " + (document.document.fileName.isEmpty()
                            ? "без имени" : Markup.body(document.document.fileName)),
                    document.caption);
            case TdApi.MessageAnimatedEmoji emoji -> "[анимированное эмодзи] " + Markup.body(emoji.emoji);

            // «я тут», «давай встретимся здесь»
            case TdApi.MessageLocation location -> "[геопозиция] %.4f, %.4f"
                    .formatted(location.location.latitude, location.location.longitude);
            case TdApi.MessageLiveLocation live -> "[геопозиция в реальном времени]";
            case TdApi.MessageVenue venue -> "[место] " + Markup.body(venue.venue.title)
                    + " — " + Markup.body(venue.venue.address);
            case TdApi.MessageProximityAlertTriggered ignored -> "[оказались рядом]";

            // номер телефона — это половина смысла присланного контакта:
            // без него непонятно, чем этим контактом вообще пользоваться
            case TdApi.MessageContact contact ->
                    "[контакт] " + Markup.body((contact.contact.firstName + " " + contact.contact.lastName).strip())
                            + (contact.contact.phoneNumber == null || contact.contact.phoneNumber.isBlank()
                                    ? "" : ", " + Markup.body(contact.contact.phoneNumber));
            case TdApi.MessageContactRegistered ignored -> "[этот человек только что появился в Telegram]";
            case TdApi.MessageUsersShared ignored -> "[поделились контактами]";
            case TdApi.MessageChatShared ignored -> "[поделились чатом]";

            // забавы
            case TdApi.MessageDice dice -> "[" + Markup.body(dice.emoji) + " выпало " + dice.value + "]";
            case TdApi.MessageStakeDice ignored -> "[кубик на ставку]";
            case TdApi.MessagePoll poll -> poll(poll);
            case TdApi.MessagePollOptionAdded ignored -> "[в опрос добавили вариант]";
            case TdApi.MessagePollOptionDeleted ignored -> "[из опроса убрали вариант]";
            case TdApi.MessageGame game -> "[игра] " + Markup.body(game.game.title)
                    + " — " + Markup.body(game.game.description);
            case TdApi.MessageGameScore score -> "[результат в игре] " + score.score;
            case TdApi.MessageStory ignored -> "[история]";
            case TdApi.MessageChecklist ignored -> "[список дел]";
            case TdApi.MessageChecklistTasksDone ignored -> "[отметили дела в списке]";
            case TdApi.MessageChecklistTasksAdded ignored -> "[в список добавили дела]";

            // звонки: пропущенный — отдельное событие
            case TdApi.MessageCall call -> call(call);
            case TdApi.MessageGroupCall ignored -> "[групповой звонок]";
            case TdApi.MessageVideoChatScheduled ignored -> "[видеовстреча назначена]";
            case TdApi.MessageVideoChatStarted ignored -> "[видеовстреча началась]";
            case TdApi.MessageVideoChatEnded ignored -> "[видеовстреча закончилась]";
            case TdApi.MessageInviteVideoChatParticipants ignored -> "[позвали на видеовстречу]";

            // подарки — повод для чувств, а не служебная запись
            case TdApi.MessageGift ignored -> "[подарок]";
            case TdApi.MessageUpgradedGift ignored -> "[улучшенный подарок]";
            case TdApi.MessageRefundedUpgradedGift ignored -> "[подарок вернули]";
            case TdApi.MessageUpgradedGiftPurchaseOffer ignored -> "[предложение купить подарок]";
            case TdApi.MessageUpgradedGiftPurchaseOfferRejected ignored -> "[предложение о подарке отклонено]";
            case TdApi.MessageGiftedPremium premium -> "[подарил премиум на " + premium.monthCount + " мес]";
            case TdApi.MessagePremiumGiftCode ignored -> "[код на премиум в подарок]";
            case TdApi.MessageGiftedStars stars -> "[подарил " + stars.starCount + " звёзд]";
            case TdApi.MessageGiftedTon ton -> "[подарил TON]";

            // розыгрыши в каналах
            case TdApi.MessageGiveaway ignored -> "[розыгрыш]";
            case TdApi.MessageGiveawayCreated ignored -> "[объявлен розыгрыш]";
            case TdApi.MessageGiveawayCompleted completed ->
                    "[розыгрыш завершён] победителей: " + completed.winnerCount;
            case TdApi.MessageGiveawayWinners winners ->
                    "[победители розыгрыша] " + winners.winnerCount;
            case TdApi.MessageGiveawayPrizeStars prize -> "[приз розыгрыша] " + prize.starCount + " звёзд";

            // деньги
            case TdApi.MessageInvoice ignored -> "[счёт на оплату]";
            case TdApi.MessagePaymentSuccessful payment ->
                    "[оплачено] %d %s".formatted(payment.totalAmount, payment.currency);
            case TdApi.MessagePaymentSuccessfulBot payment ->
                    "[оплачено] %d %s".formatted(payment.totalAmount, payment.currency);
            case TdApi.MessagePaymentRefunded ignored -> "[оплату вернули]";
            case TdApi.MessagePaidMedia ignored -> "[платное вложение]";
            case TdApi.MessagePaidMessagesRefunded refunded ->
                    "[вернули оплату за сообщения] " + refunded.messageCount;
            case TdApi.MessagePaidMessagePriceChanged ignored -> "[изменилась цена сообщений]";
            case TdApi.MessageDirectMessagePriceChanged ignored -> "[изменилась цена личных сообщений]";

            // исчезающее: посмотреть уже нельзя, но знать, что было, важно
            case TdApi.MessageExpiredPhoto ignored -> "[исчезнувшее фото]";
            case TdApi.MessageExpiredVideo ignored -> "[исчезнувшее видео]";
            case TdApi.MessageExpiredVideoNote ignored -> "[исчезнувший кружок]";
            case TdApi.MessageExpiredVoiceNote ignored -> "[исчезнувшее голосовое]";
            case TdApi.MessageScreenshotTaken ignored -> "[сделали скриншот переписки]";

            // что происходит вокруг в группах и каналах
            case TdApi.MessagePinMessage pinned ->
                    "[закрепили сообщение] message_id=" + pinned.messageId;
            case TdApi.MessageChatAddMembers add ->
                    "[в чат добавили участников] " + (add.memberUserIds == null ? 0 : add.memberUserIds.length);
            case TdApi.MessageChatJoinByLink ignored -> "[присоединился по ссылке]";
            case TdApi.MessageChatJoinByRequest ignored -> "[приняли заявку в чат]";
            case TdApi.MessageChatDeleteMember left -> "[участник вышел] user_id=" + left.userId;
            case TdApi.MessageChatChangeTitle title -> "[чат переименован] " + Markup.body(title.title);
            case TdApi.MessageChatChangePhoto ignored -> "[сменили фото чата]";
            case TdApi.MessageChatDeletePhoto ignored -> "[убрали фото чата]";
            case TdApi.MessageBasicGroupChatCreate created -> "[создана группа] " + Markup.body(created.title);
            case TdApi.MessageSupergroupChatCreate created -> "[создана супергруппа] "
                    + Markup.body(created.title);
            case TdApi.MessageChatUpgradeTo ignored -> "[группа стала супергруппой]";
            case TdApi.MessageChatUpgradeFrom ignored -> "[выросла из обычной группы]";
            case TdApi.MessageChatOwnerLeft ignored -> "[владелец покинул чат]";
            case TdApi.MessageChatOwnerChanged ignored -> "[у чата сменился владелец]";
            case TdApi.MessageChatSetTheme ignored -> "[сменили оформление чата]";
            case TdApi.MessageChatSetBackground ignored -> "[сменили фон чата]";
            case TdApi.MessageChatSetMessageAutoDeleteTime ignored -> "[задали срок жизни сообщений]";
            case TdApi.MessageChatHasProtectedContentToggled ignored -> "[переключили защиту содержимого]";
            case TdApi.MessageChatHasProtectedContentDisableRequested ignored ->
                    "[запросили снятие защиты содержимого]";
            case TdApi.MessageChatBoost ignored -> "[чат поддержали бустом]";

            case TdApi.MessageForumTopicCreated ignored -> "[создана тема]";
            case TdApi.MessageForumTopicEdited ignored -> "[тему изменили]";
            case TdApi.MessageForumTopicIsClosedToggled ignored -> "[тему закрыли или открыли]";
            case TdApi.MessageForumTopicIsHiddenToggled ignored -> "[тему скрыли или показали]";

            // предложенные посты в каналах
            case TdApi.MessageSuggestedPostApproved ignored -> "[предложенный пост одобрен]";
            case TdApi.MessageSuggestedPostDeclined ignored -> "[предложенный пост отклонён]";
            case TdApi.MessageSuggestedPostApprovalFailed ignored -> "[пост не удалось одобрить]";
            case TdApi.MessageSuggestedPostPaid ignored -> "[за пост заплатили]";
            case TdApi.MessageSuggestedPostRefunded ignored -> "[оплату за пост вернули]";

            case TdApi.MessageSuggestProfilePhoto ignored -> "[предложили фото профиля]";
            case TdApi.MessageSuggestBirthdate ignored -> "[предложили указать день рождения]";
            case TdApi.MessageBotWriteAccessAllowed ignored -> "[разрешили боту писать]";
            case TdApi.MessageManagedBotCreated ignored -> "[создан управляемый бот]";
            case TdApi.MessageWebAppDataSent ignored -> "[отправлены данные приложения]";
            case TdApi.MessageWebAppDataReceived ignored -> "[получены данные приложения]";
            case TdApi.MessagePassportDataSent ignored -> "[отправлены документы]";
            case TdApi.MessagePassportDataReceived ignored -> "[получены документы]";
            case TdApi.MessageCustomServiceAction action -> "[служебное] " + Markup.body(action.text);

            case TdApi.MessageUnsupported ignored -> "[сообщение, которое здесь не показать]";
            case null -> "";
            // на случай, если в Telegram появится что-то новое: лучше странная
            // пометка, чем молчание о том, что сообщение вообще было
            default -> "[" + content.getClass().getSimpleName().replace("Message", "") + "]";
        };
    }

    private static String poll(TdApi.MessagePoll message) {
        StringBuilder out = new StringBuilder("[опрос] ").append(Markup.body(message.poll.question.text));
        if (message.poll.options != null) {
            for (TdApi.PollOption option : message.poll.options) {
                out.append("\n- ").append(Markup.body(option.text.text));
            }
        }
        return out.toString();
    }

    /** Звонок размечен тегом: на него потом можно сослаться так же, как на вложение. */
    private static String call(TdApi.MessageCall call) {
        String outcome = call.discardReason instanceof TdApi.CallDiscardReasonMissed ? " missed"
                : call.discardReason instanceof TdApi.CallDiscardReasonDeclined ? " declined" : "";
        return "<call is_video=\"%s\" duration=\"%d\"%s />".formatted(call.isVideo, call.duration, outcome);
    }

    private static String linkPreview(TdApi.LinkPreview preview) {
        StringBuilder out = new StringBuilder("<link_preview");
        attribute(out, "url", preview.url);
        attribute(out, "site", preview.siteName);
        attribute(out, "title", preview.title);
        attribute(out, "author", preview.author);
        String description = preview.description == null ? "" : Markup.body(preview.description.text);
        return description.isEmpty()
                ? out.append(" />").toString()
                : out.append(">\n").append(description).append("\n</link_preview>").toString();
    }

    /** Превью рисует сам сайт: его тексты приходят в разметку извне. */
    private static void attribute(StringBuilder out, String name, String value) {
        String safe = Markup.attribute(value);
        if (!safe.isEmpty()) {
            out.append(' ').append(name).append("=\"").append(safe).append('"');
        }
    }

    private static String withCaption(String prefix, TdApi.FormattedText caption) {
        return caption == null || caption.text.isEmpty()
                ? prefix : prefix + "\n" + Markup.body(caption.text);
    }
}
