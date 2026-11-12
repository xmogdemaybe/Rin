package ru.rainedev.raine.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Скользящее окно на чат: пока в него пишут одно за другим, она к телефону не
 * тянется. Фиксированная пауза на каждое сообщение этого не давала — три реплики
 * с разницей в пару секунд превращались в три хода, и последний отвечал на
 * первое.
 */
public final class NoticeWindow implements AutoCloseable {

    /** До скольки секунд откладывать ход, если сообщения сыплются без конца. */
    public static final long CEILING_MILLIS = 15_000;

    /** Чем отложить. В бою — планировщик на виртуальных потоках, в тестах — подставной. */
    public interface Delayer {
        /** Выполнить action через millis; вернуть способ его отменить. */
        Runnable after(long millis, Runnable action);
    }

    /** Боевой планировщик: один поток, задачи — на виртуальных. */
    private static final class Threads implements Delayer {
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("rin-notice").factory());

        @Override
        public Runnable after(long millis, Runnable action) {
            ScheduledFuture<?> task = scheduler.schedule(action, millis, TimeUnit.MILLISECONDS);
            return () -> task.cancel(false);
        }

        private void shutdown() {
            scheduler.shutdownNow();
        }
    }

    private record Pending(Runnable cancel, long deadline) {}

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Delayer delayer;
    private final LongSupplier clock;
    private final long ceiling;

    public NoticeWindow() {
        this(new Threads(), System::currentTimeMillis, CEILING_MILLIS);
    }

    NoticeWindow(Delayer delayer, LongSupplier clock, long ceiling) {
        this.delayer = delayer;
        this.clock = clock;
        this.ceiling = ceiling;
    }

    /**
     * Отложить action по ключу (чату) на windowMillis. Новое сообщение того же
     * чата снимает прошлый хвост и заводит новый — но не дальше потолка,
     * отсчитанного от первого: поток сообщений не должен отменять ход навсегда.
     */
    public void defer(long key, long windowMillis, Runnable action) {
        long now = clock.getAsLong();
        Pending previous = pending.remove(key);
        long window = Math.max(0, windowMillis);
        // потолок первую же выдержку не урезает: полоса «глянула на экран
        // блокировки» измеряется от своего сообщения, а не от чужого терпения
        long deadline = previous != null ? previous.deadline() : now + Math.max(window, ceiling);
        if (previous != null) {
            previous.cancel().run();
        }
        long wait = Math.min(window, Math.max(0, deadline - now));
        Runnable done = () -> {
            pending.remove(key);
            action.run();
        };
        pending.put(key, new Pending(delayer.after(wait, done), deadline));
    }

    @Override
    public void close() {
        pending.values().forEach(pending -> pending.cancel().run());
        pending.clear();
        if (delayer instanceof Threads threads) {
            threads.shutdown();
        }
    }
}
