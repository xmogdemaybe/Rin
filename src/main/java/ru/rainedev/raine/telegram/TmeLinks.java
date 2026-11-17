package ru.rainedev.raine.telegram;

import it.tdlight.jni.TdApi;
import java.util.List;
import java.util.Optional;
import ru.rainedev.raine.phone.MessageFormatter;
import ru.rainedev.raine.phone.TmeLink;

/**
 * Раскрывает ссылки на посты, чтобы разметка читала их уже готовыми.
 * <p>
 * Открытый чат просматривается целиком, а пост за ссылкой — это ещё один запрос
 * к Telegram. Поэтому за один экран раскрывается не больше трёх ссылок и только
 * те, что встретились в свежепрочитанных сообщениях. Ответ живёт час: посты правят
 * не так часто, чтобы переспрашивать при каждом открытии чата, а промах — тоже
 * ответ: битый адрес иначе долбил бы Telegram заново.
 */
public final class TmeLinks implements MessageFormatter.LinkLookup {

    /** Спросить Telegram, что стоит за адресом. */
    @FunctionalInterface
    interface Resolver {
        Optional<TdApi.MessageLinkInfo> resolve(String url);
    }

    /** Спросить Telegram, что стоит за именем объекта: канал, человек, группа. */
    @FunctionalInterface
    interface ObjectResolver {
        Optional<ru.rainedev.raine.phone.TelegramObject> resolve(String username);
    }

    /** Объектов на экран не больше двух: каждый — это поиск чата, описание и история. */
    static final int OBJECTS_PER_SCREEN = 2;

    private static final long HOUR = 60 * 60_000;

    private final ShortLivedCache<String, Optional<TdApi.MessageLinkInfo>> cache =
            new ShortLivedCache<>(HOUR);

    private final ShortLivedCache<String, Optional<ru.rainedev.raine.phone.TelegramObject>> objects =
            new ShortLivedCache<>(HOUR);

    private final Resolver resolver;
    private final ObjectResolver objectResolver;

    /** Кому сдавать найденные объекты как наводки владельца; без реестра некуда. */
    private volatile ru.rainedev.raine.core.OwnerLeads leads;

    TmeLinks(Resolver resolver) {
        this(resolver, username -> Optional.empty());
    }

    TmeLinks(Resolver resolver, ObjectResolver objectResolver) {
        this.resolver = resolver;
        this.objectResolver = objectResolver;
    }

    public TmeLinks(Telegram telegram) {
        this(telegram::linkInfo, telegram::publicObject);
    }

    /**
     * Раскрытая по ссылке карточка канала или человека — это наводка, если её дал
     * владелец: решение «да, напиши» приходит следующим сообщением, а карточка к
     * тому моменту уже прочитана. Различает чей ход сам реестр.
     */
    public void leads(ru.rainedev.raine.core.OwnerLeads registry) {
        this.leads = registry;
    }

    /** Разметка только читает то, что уже раскрыли: сама она к Telegram не ходит. */
    @Override
    public Optional<TdApi.MessageLinkInfo> peek(String url) {
        return cache.cached(url).orElse(Optional.empty());
    }

    @Override
    public Optional<ru.rainedev.raine.phone.TelegramObject> peekObject(String username) {
        return objects.cached(username).orElse(Optional.empty());
    }

    @Override
    public void lookAhead(List<TdApi.Message> messages) {
        int budget = TmeLink.PER_SCREEN;
        int objectBudget = OBJECTS_PER_SCREEN;
        for (TdApi.Message message : messages) {
            if (budget == 0 && objectBudget == 0) {
                return;
            }
            for (String url : TmeLink.in(message, TmeLink.PER_MESSAGE)) {
                if (budget == 0) {
                    break;
                }
                if (cache.cached(url).isPresent()) {
                    continue;   // раскрытое раньше ничего не стоит и не тратит бюджет
                }
                budget--;
                cache.get(url, resolver::resolve);
            }
            for (String username : TmeLink.objectsIn(message, TmeLink.PER_MESSAGE)) {
                if (objectBudget == 0) {
                    break;
                }
                if (objects.cached(username).isPresent()) {
                    continue;
                }
                objectBudget--;
                Optional<ru.rainedev.raine.phone.TelegramObject> found =
                        objects.get(username, objectResolver::resolve);
                if (leads != null) {
                    found.ifPresent(object -> leads.vouch(object.chat().id));
                }
            }
        }
    }
}
