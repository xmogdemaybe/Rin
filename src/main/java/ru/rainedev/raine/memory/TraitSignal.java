package ru.rainedev.raine.memory;

import java.time.Duration;

/**
 * Заметка модели о себе: что задело, какой чертой это отзывается и насколько сильно.
 * <p>
 * Сама по себе она ничего не меняет: заметки видит только ночной отбор.
 * Черта закрепляется, когда наблюдение повторилось, либо когда оно единственное,
 * но очень сильное.
 *
 * @param kind      вид события: hurt, joy, preference, attitude и подобное
 * @param trait     сама черта короткой формулировкой: «огрызаюсь, если колют»
 * @param valence   приятно или нет: -1..1
 * @param intensity насколько сильно задело: 0..1
 * @param subject   к кому относится: имя человека, иначе пусто — про себя вообще
 * @param at        когда замечено (ISO)
 */
public record TraitSignal(String kind, String trait, double valence, double intensity, String subject, String at) {

    /** Сколько заметка ждёт своего повтора: старше — её уже не перечитывают. */
    public static final Duration KEEP = Duration.ofDays(14);

    /**
     * Чем одна заметка отличается от другой. Формулировку модель пишет свободно,
     * поэтому вид и черта сравниваются без регистра: «Огрызаюсь» и «огрызаюсь» —
     * одна заметка, а не две.
     */
    public String key() {
        return kind.strip().toLowerCase() + "|" + trait.strip().toLowerCase() + "|" + at;
    }
}
