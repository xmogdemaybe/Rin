package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Находит в чужом тексте ссылки на конкретный пост Telegram.
 * <p>
 * Сам адрес раскрывает Telegram: он знает и публичные «t.me/durov/123», и
 * «t.me/c/…», куда у аккаунта есть доступ. Здесь только отбор — куда вообще
 * смотреть, чтобы одна пересланная ссылка не превращала переписку в ленту
 * пересказов.
 */
public final class TmeLink {

    /** Больше двух ссылок на сообщение — это уже не вопрос, а чужая лента. */
    public static final int PER_MESSAGE = 2;

    /** И три на открытый чат: каждое раскрытие стоит обращения к Telegram. */
    public static final int PER_SCREEN = 3;

    /**
     * Пост — это адрес, где после имени канала или «c/номер» идёт номер сообщения;
     * всё остальное раскрывать нечем. Адрес с протоколом, без него и со старым
     * хостом. Точка, «?» и «#» в класс символов не входят, поэтому фраза
     * «смотри t.me/a/1.» не превращается в несуществующий адрес, а хвост
     * «?single» не уезжает вместе со ссылкой.
     */
    private static final Pattern POST = Pattern.compile(
            "(?<![A-Za-z0-9._~-])(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me)/[A-Za-z0-9_/-]*/\\d+\\b");

    private TmeLink() {}

    /** Ссылки в порядке появления, не больше «limit» и без повторов. */
    public static List<String> find(String text, int limit) {
        Set<String> found = new LinkedHashSet<>();
        Matcher address = POST.matcher(text == null ? "" : text);
        while (found.size() < limit && address.find()) {
            found.add(address.group());
        }
        return List.copyOf(found);
    }

    /** То же для всего, что человек написал в этом сообщении: текст и подпись к вложению. */
    public static List<String> in(TdApi.Message message, int limit) {
        return find(wordsOf(message.content), limit);
    }

    /** Строки, которые набрал человек, — без разметки, которую добавили мы сами. */
    private static String wordsOf(TdApi.MessageContent content) {
        return switch (content) {
            case TdApi.MessageText text -> text.text.text;
            case TdApi.MessagePhoto photo -> caption(photo.caption);
            case TdApi.MessageVideo video -> caption(video.caption);
            case TdApi.MessageAnimation animation -> caption(animation.caption);
            case TdApi.MessageVoiceNote voice -> caption(voice.caption);
            case TdApi.MessageAudio audio -> caption(audio.caption);
            case TdApi.MessageDocument document -> caption(document.caption);
            case TdApi.MessagePaidMedia paid -> caption(paid.caption);
            case null, default -> "";
        };
    }

    private static String caption(TdApi.FormattedText caption) {
        return caption == null ? "" : caption.text;
    }
}
