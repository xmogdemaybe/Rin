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

    private static final long HOUR = 60 * 60_000;

    private final ShortLivedCache<String, Optional<TdApi.MessageLinkInfo>> cache =
            new ShortLivedCache<>(HOUR);

    private final Resolver resolver;

    TmeLinks(Resolver resolver) {
        this.resolver = resolver;
    }

    public TmeLinks(Telegram telegram) {
        this(telegram::linkInfo);
    }

    /** Разметка только читает то, что уже раскрыли: сама она к Telegram не ходит. */
    @Override
    public Optional<TdApi.MessageLinkInfo> peek(String url) {
        return cache.cached(url).orElse(Optional.empty());
    }

    @Override
    public void lookAhead(List<TdApi.Message> messages) {
        int budget = TmeLink.PER_SCREEN;
        for (TdApi.Message message : messages) {
            if (budget == 0) {
                return;
            }
            for (String url : TmeLink.in(message, TmeLink.PER_MESSAGE)) {
                if (cache.cached(url).isPresent()) {
                    continue;   // раскрытое раньше ничего не стоит и не тратит бюджет
                }
                budget--;
                cache.get(url, resolver::resolve);
            }
        }
    }
}
