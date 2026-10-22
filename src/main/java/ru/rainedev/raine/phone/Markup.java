package ru.rainedev.raine.phone;

import java.util.regex.Pattern;

/**
 * Переписку и списки модель видит как разметку: {@code <message sender="..">}.
 * В неё попадают чужие тексты — имена людей, заголовки чатов, описания ссылок,
 * результаты поиска, расшифровки голоса. Без оглядки такой текст стоит ему
 * содержать «</message>», закрывает чужой блок и дописывает реплику от имени
 * того, кому она доверяет. Гасится только открывающая скопка перед буквой или
 * слэхом и служебные маркеры: слова остаются словами, а границы блоков больше
 * не зависят от чужого текста.
 */
public final class Markup {

    /** «<3» и «a < b» доживают: опасна скопка именно перед именем тега. */
    private static final Pattern TAG_LIKE = Pattern.compile("<(?=[a-zA-Z/])");

    private Markup() {}

    /** Текст внутри блока: переносы остаются, чужая разметка — нет. */
    public static String body(String incoming) {
        return TAG_LIKE.matcher(ControlMarkers.sanitize(incoming)).replaceAll("");
    }

    /** Значение атрибута: одна строка, без кавычек и без попыток открыть тег. */
    public static String attribute(String incoming) {
        String text = body(incoming).replace('"', '\'');
        return text.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").strip();
    }
}
