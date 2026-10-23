package ru.rainedev.raine.telegram;

import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Кому видна её собственная история.
 * <p>
 * Решает владелец настройкой, а не она сама: историю видят живые люди, и она
 * висит сутки, а попросить «покажись всему миру» легко из обычного разговора.
 */
public enum StoryAudience {

    /** Только те, кого она держит в контактах. */
    CONTACTS,

    /** Любой, кто её увидит. */
    EVERYONE,

    /** Ближний круг — он заведён в самом Telegram, список наружу не отдаётся. */
    CLOSE_FRIENDS;

    private static final Logger log = LoggerFactory.getLogger(StoryAudience.class);

    /**
     * Незнакомое слово сужает круг, а не расширяет: опечатка в «everyone» не
     * должна оборачиваться снимком, открытым всему Telegram. О ней говорим —
     * написавший «world» ждёт совсем не контактов.
     */
    public static StoryAudience of(String value) {
        String written = value == null ? "" : value.trim().toLowerCase();
        return switch (written) {
            case "everyone", "all" -> EVERYONE;
            case "close_friends", "friends" -> CLOSE_FRIENDS;
            case "", "contacts", "contacts_only" -> CONTACTS;
            default -> {
                log.warn("Не знаю такой аудитории историй: «{}» — показываю контактам", value);
                yield CONTACTS;
            }
        };
    }

    /** Запрос TDLib. Пустой список исключений — «никого не отсекаем». */
    TdApi.StoryPrivacySettings request() {
        return switch (this) {
            case EVERYONE -> new TdApi.StoryPrivacySettingsEveryone(new long[0]);
            case CLOSE_FRIENDS -> new TdApi.StoryPrivacySettingsCloseFriends();
            case CONTACTS -> new TdApi.StoryPrivacySettingsContacts(new long[0]);
        };
    }

    /** Кому она покажется — этими словами она сама и отвечает за выбор. */
    public String described() {
        return switch (this) {
            case EVERYONE -> "anyone who sees it";
            case CLOSE_FRIENDS -> "your close friends only";
            case CONTACTS -> "the people in your contacts";
        };
    }
}
