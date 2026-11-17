package ru.rainedev.raine.vision;

/**
 * Сколько свежих просмотров зрение ещё успевает в один список.
 * <p>
 * Описание из кэша ничего не стоит, а каждый неразгляденный файл — это
 * скачивание и запрос к модели зрения. Пак в тридцать четыре стикера обходится
 * в минуты, а когда квота уже кончилась, каждый файл ещё и бьётся о отказ по
 * три попытки. Поэтому у свежих просмотров есть предел, а первый же отказ
 * закрывает остаток списка.
 */
public final class Glimpses {

    private final int limit;
    private int spent;
    private boolean refused;

    public Glimpses(int limit) {
        this.limit = Math.max(0, limit);
    }

    /** Кэш смотрим всегда; свежий взгляд — пока есть лимит и зрение не отказало. */
    public boolean allows(boolean cached) {
        return cached || (!refused && spent < limit);
    }

    /** Чем кончился взгляд: пусто — значит зрение молчит, и в остальные не стучимся. */
    public void spent(String looked) {
        spent++;
        if (looked == null || looked.isBlank()) {
            refused = true;
        }
    }

    public int spent() {
        return spent;
    }

    public boolean refused() {
        return refused;
    }
}
