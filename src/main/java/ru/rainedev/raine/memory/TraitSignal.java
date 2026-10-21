package ru.rainedev.raine.memory;

/**
 * Заметка модели о себе: что задело, какой чертой это отзывается и насколько сильно.
 * <p>
 * Сама по себе пилюля ничего не меняет — заметки видит только ночной отбор.
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
public record TraitSignal(String kind, String trait, double valence, double intensity, String subject, String at) {}
