package ru.rainedev.raine.phone;

import it.tdlight.jni.TdApi;

/**
 * Описывает вложение словами. Отдельный интерфейс, потому что разметка
 * сообщений — чистое преобразование, а разглядывание картинки и распознавание
 * голоса ходят в сеть.
 */
@FunctionalInterface
public interface MediaDescriber {

    /** @return описание для подстановки под вложение или пустая строка */
    String describe(TdApi.Message message);

    /**
     * Короткое имя пака по его номеру — то, чем открывают {@code #sticker_pack}.
     * <p>
     * По умолчанию пусто: разметка истории обязана отвечать без обращения к
     * Telegram, иначе открытие группового чата с десятком чужих стикеров
     * выстроит десяток запросов ради одного атрибута.
     */
    default String packName(long setId) {
        return "";
    }

    /**
     * Есть ли чем разглядывать. Разбор вложений подключён и без глаз: подпись
     * истории читается так же, как обычное сообщение, а голосовое слушают уши.
     * Но греть заранее то, чего всё равно не увидят, — значит гонять сеть впустую.
     */
    default boolean sees() {
        return true;
    }

    /**
     * Есть ли чем слушать: слух работает и без зрения. Греть заранее то, для чего
     * ушей нет, незачем по той же причине, поэтому по умолчанию — нет.
     */
    default boolean hears() {
        return false;
    }

    /** Восприятие не подключено: видно только тип вложения. */
    MediaDescriber NONE = new MediaDescriber() {
        @Override
        public String describe(TdApi.Message message) {
            return "";
        }

        @Override
        public boolean sees() {
            return false;
        }
    };
}
