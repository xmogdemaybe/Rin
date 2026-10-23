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
     * Есть ли чем разглядывать. Разбор вложений подключён и без глаз: подпись
     * истории читается так же, как обычное сообщение. Но греть заранее то, чего
     * всё равно не увидят, — значит гонять сеть впустую.
     */
    default boolean sees() {
        return true;
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
