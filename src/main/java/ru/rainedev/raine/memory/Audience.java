package ru.rainedev.raine.memory;

/**
 * Кто смотрит в память прямо сейчас.
 * <p>
 * Отсечка живёт в коде, а не в промпте: попросить чужой секрет у модели можно
 * теми же словами, что и свой, — а различие видно только по цифрам, которые она
 * сама должна была бы сопоставить со строками файла доверия.
 *
 * @param chatId чат, из которого смотрят; 0 — смотрит не переписка
 * @param trust  доверие к тому, кому отвечают; нет человека в файле — ноль
 * @param owner  это личка владельца: ей видно всё
 */
public record Audience(long chatId, int trust, boolean owner) {

    /** Зритель не определён: событие не из переписки и не про человека. */
    public static final Audience STRANGER = new Audience(0, 0, false);

    /**
     * Показывать ли этому зрителю запись, пришедшую из чата sourceChat под
     * меткой source.
     * <p>
     * Доверие открывает чужие лички, но не бункер владельца: то, что он
     * рассказал в своём личном чате, не становится общим ни за какую оценку.
     */
    public boolean allows(Source source, long sourceChat) {
        if (owner) {
            return true;
        }
        if (source == Source.OWNER_DM) {
            return false;
        }
        if (trust >= 1) {
            return true;
        }
        // случайному человеку остаётся общее и сказанное в его же разговоре.
        // Ноль чата зрителя при этом обязан не совпадать с чужой записью без
        // адреса: иначе событие вне переписки вытаскивало бы чужие лички
        return source == Source.GENERAL || (chatId != 0 && sourceChat == chatId);
    }

    /**
     * Откуда запись — словами, которые встают внутрь блока записи. Общее не
     * подписывают: источника у него нет, а строка о нём была бы шумом.
     */
    public String attributionOf(Source source, long sourceChat) {
        boolean here = chatId != 0 && sourceChat == chatId;
        return switch (source) {
            case OWNER_DM -> "[from: your private chat with your person]";
            case PRIVATE_DM -> here
                    ? "[from: this private chat]"
                    : "[from: a private chat with someone else]";
            case GROUP -> here ? "[from: this chat]" : "[from: another chat]";
            case GENERAL -> "";
        };
    }
}
