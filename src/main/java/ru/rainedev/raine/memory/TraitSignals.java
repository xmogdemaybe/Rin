package ru.rainedev.raine.memory;

import java.util.ArrayList;
import java.util.List;

/** Копилка заметок текущего разговора: модель пишет, ночной отбор разбирает. */
public final class TraitSignals {

    private final List<TraitSignal> pending = new ArrayList<>();

    public synchronized void add(TraitSignal signal) {
        pending.add(signal);
    }

    /** Забирает всё накопленное: копилка опустошается, заметки ждут записи дневника. */
    public synchronized List<TraitSignal> drain() {
        List<TraitSignal> taken = List.copyOf(pending);
        pending.clear();
        return taken;
    }

    /** Запись дневника не случилась — заметки живут дальше, до следующему разу. */
    public synchronized void requeue(List<TraitSignal> signals) {
        pending.addAll(signals);
    }
}
