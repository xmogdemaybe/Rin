package ru.rainedev.raine.speech;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;
import org.jcodec.codecs.mpeg4.mp4.EsdsBox;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.MP4TrackType;
import org.jcodec.containers.mp4.boxes.NodeBox;
import org.jcodec.containers.mp4.boxes.SampleEntry;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Достаёт звуковую дорожку из видео — сырым звуком, каким его понимает
 * разговорная модель.
 * <p>
 * Всё делается своими силами: контейнер MP4 разбирается и AAC декодируется
 * чистой Java, дальше звук сводится в моно и пересчитывается под речь.
 * Внешних программ бот не зовёт — иначе на машине без них видео молча
 * становилось бы немым.
 * <p>
 * Разбирается MP4 с дорожкой AAC — то, чем оказывается почти всякое видео
 * из переписки. Остальное остаётся немым, как и раньше: это не ошибка.
 */
public final class VideoAudio {

    private static final Logger log = LoggerFactory.getLogger(VideoAudio.class);

    /** Речь распознаётся и с такой частоты, а файл выходит втрое легче. */
    private static final int TARGET_RATE = 16_000;

    /**
     * Дольше этого разбор не идёт ни при каких условиях: на битом контейнере
     * библиотека способна уйти в вечный цикл (проверено на файле, который
     * видео только притворяется).
     */
    private static final int TIMEOUT_SECONDS = 60;

    /**
     * Сколько секунд ждём, пока отменённый разбор отдаст файл. Обычно он выходит
     * сразу, как только заметит прерывание или закрытый канал; столько остаётся
     * на редкий случай, где не выходит никуда.
     */
    private static final int STOP_GRACE_SECONDS = 5;

    /** Длина заголовка ADTS без контрольной суммы. */
    private static final int ADTS_HEADER = 7;

    /**
     * Тише этого в дорожке ничего нет. Речь и цифровая тишина различаются на
     * порядки: даже шёпот громче шума квантования во много раз.
     */
    private static final int SILENCE_PEAK = 256;

    /** С такой подписи начинается всякий MP4; всё прочее нам не по зубам. */
    private static final List<String> KNOWN_BOXES = List.of("ftyp", "moov", "mdat", "free", "skip", "wide");

    private VideoAudio() {}

    /**
     * Дорожка сырым звуком.
     * <p>
     * Нужно это там, где из видео берут только речь: разговорной модели картинку
     * в запрос не положить, а упакованный в ogg звук она не принимает.
     *
     * @param maxSeconds сколько секунд дорожки брать: ролик из переписки — это
     *                   минуты, а держать в памяти дорожку от часового фильма
     *                   ни к чему. Лимит спрашивают снаружи и до тяжёлой работы:
     *                   проверять её после декодирования значит платить за то,
     *                   что потом выбросить
     * @return отсчёты одного канала или пусто, если звука нет или формат не поддался
     */
    public static Optional<Sound> sound(Path video, int maxSeconds) {
        if (!looksLikeMp4(video)) {
            log.debug("Это не MP4 — звук останется неуслышанным");
            return Optional.empty();
        }
        // разбор идёт в стороне от общего потока: чужой файл может оказаться
        // таким, что библиотека на нём зациклится, и ждать её вечно мы не станем
        AtomicReference<AutoCloseable> opened = new AtomicReference<>();
        return guard(TIMEOUT_SECONDS, () -> decodeTrack(video, maxSeconds, opened), opened);
    }

    /**
     * Разбор с дедлайном.
     * <p>
     * Прерывание слышит только тот, кто на него смотрит, а библиотека внутри
     * своего цикла его не замечает вовсе. Поэтому по истечении срока отменяют не
     * только ожидание, но и канал, который разбор держит открытым: один лишь
     * cancel оставил бы дескриптор файла висеть до конца работы бота, и таких
     * накопилось бы по числу битых роликов.
     * <p>
     * Видно пакету: так проверяют и то, что готовый результат возвращается, и то,
     * что разбор, который прерываний не слушает, всё равно отдаёт файл.
     *
     * @param opened сюда разбор отмечает свой канал, как только его открыл
     */
    static Optional<Sound> guard(int timeoutSeconds, Callable<Optional<Sound>> parse,
                                 AtomicReference<AutoCloseable> opened) {
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "video-audio");
            // если разбор всё-таки завис, он не помешает боту остановиться
            thread.setDaemon(true);
            return thread;
        });
        Future<Optional<Sound>> pending = worker.submit(parse);
        try {
            return pending.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.debug("Разбор звука не уложился в {} с — слушать не будем", timeoutSeconds);
            return Optional.empty();
        } catch (ExecutionException e) {
            log.debug("Звук из видео не разобрался: {}", e.getCause().getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            pending.cancel(true);
            close(opened);
            worker.shutdownNow();
            awaitStop(worker);
        }
    }

    /**
     * Дать прерванному разбору договорить: он выходит из цикла, лишь заметив
     * прерывание или закрытый канал, и только тогда файл освобождён по-настоящему.
     * Не дождались — говорим прямо, а не делаем вид, что дескриптор вернулся.
     */
    private static void awaitStop(ExecutorService worker) {
        try {
            if (!worker.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Разбор звука не остановился и после отмены — файл, возможно, всё ещё открыт");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Канал мог и не открыться: разбор до него не дошёл. */
    private static void close(AtomicReference<AutoCloseable> opened) {
        AutoCloseable channel = opened.get();
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception e) {
            log.debug("Канал разбора не закрылся: {}", e.getMessage());
        }
    }

    /** Первый блок MP4 подписан своим именем — по нему и узнаём формат. */
    private static boolean looksLikeMp4(Path video) {
        try (RandomAccessFile file = new RandomAccessFile(video.toFile(), "r")) {
            byte[] head = new byte[8];
            file.readFully(head);
            return KNOWN_BOXES.contains(new String(head, 4, 4, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * @param opened сюда отмечаем канал, едва он открыт: ждущий разбор с дедлайном
     *               закрывает его сам, если разбор до своей остановки не дошёл
     */
    private static Optional<Sound> decodeTrack(Path video, int maxSeconds, AtomicReference<AutoCloseable> opened) {
        Sound taken;
        try (FileChannelWrapper channel = NIOUtils.readableChannel(video.toFile())) {
            opened.set(channel);
            Optional<Aac> track = audioTrack(MP4Demuxer.createMP4Demuxer(channel));
            if (track.isEmpty()) {
                log.debug("В видео нет разбираемой звуковой дорожки");
                return Optional.empty();
            }
            Sound sound = decode(track.get(), maxSeconds);
            if (sound == null || sound.samples().length == 0) {
                return Optional.empty();
            }
            if (silent(sound.samples())) {
                // дорожка есть, а звука в ней нет: распознавание речи на тишине
                // не молчит, а выдумывает субтитры
                log.debug("Дорожка оказалась тишиной — слушать нечего");
                return Optional.empty();
            }
            taken = sound;
        } catch (IOException | RuntimeException e) {
            // неподдержанный контейнер — обычное дело, разговор из-за этого не ломаем
            log.debug("Звук из видео не разобрался: {}", e.getMessage());
            return Optional.empty();
        }
        log.debug("Дорожка вынута: {} с", taken.samples().length / taken.rate());
        return Optional.of(new Sound(Pcm.resample(taken.samples(), taken.rate(), TARGET_RATE),
                TARGET_RATE, taken.cut()));
    }

    /**
     * Звуковая дорожка вместе с её описанием. Описание читается один раз:
     * блок esds отдаёт его тем же буфером, и второму читателю не остаётся ничего.
     */
    private record Aac(AbstractMP4DemuxerTrack track, byte[] description) {}

    private static Optional<Aac> audioTrack(MP4Demuxer demuxer) {
        for (AbstractMP4DemuxerTrack track : demuxer.getTracks()) {
            if (track.getType() != MP4TrackType.SOUND) {
                continue;
            }
            // декодер у нас один — AAC; прочее (AC3, AMR) в переписке не встречается
            byte[] description = descriptionOf(track);
            if (description != null) {
                return Optional.of(new Aac(track, description));
            }
        }
        return Optional.empty();
    }

    /**
     * Описание потока из контейнера: без него декодер не знает ни частоты,
     * ни числа каналов, ни профиля. У AAC оно лежит в блоке esds.
     */
    private static byte[] descriptionOf(AbstractMP4DemuxerTrack track) {
        SampleEntry[] entries = track.getSampleEntries();
        if (entries == null || entries.length == 0) {
            return null;
        }
        EsdsBox esds = NodeBox.findFirst(entries[0], EsdsBox.class, EsdsBox.fourcc());
        if (esds == null) {
            return null;
        }
        ByteBuffer info = esds.getStreamInfo();
        if (info == null || !info.hasRemaining()) {
            return null;
        }
        byte[] description = new byte[info.remaining()];
        info.duplicate().get(description);
        return description;
    }

    /**
     * Разборщик контейнера надевает на кадр заголовок ADTS, а декодеру нужен
     * голый кадр: заголовок он принимает за звук и молча выдаёт тишину — ни
     * ошибки, ни пустого результата, просто дорожка без единого звука.
     */
    private static byte[] withoutAdts(byte[] frame) {
        boolean wrapped = frame.length > ADTS_HEADER
                && (frame[0] & 0xFF) == 0xFF && (frame[1] & 0xF0) == 0xF0;
        if (!wrapped) {
            return frame;
        }
        // младший бит второго байта: единица — защиты нет, ноль — впереди ещё CRC
        int header = (frame[1] & 0x01) == 1 ? ADTS_HEADER : ADTS_HEADER + 2;
        return Arrays.copyOfRange(frame, header, frame.length);
    }

    private static Sound decode(Aac audio, int maxSeconds) throws IOException {
        AbstractMP4DemuxerTrack track = audio.track();
        Decoder decoder = Decoder.create(audio.description());
        SampleBuffer buffer = new SampleBuffer();
        buffer.setBigEndian(false);

        List<short[]> chunks = new ArrayList<>();
        int total = 0;
        int rate = 0;
        boolean cut = false;

        Packet packet;
        while ((packet = track.nextFrame()) != null) {
            if (Thread.currentThread().isInterrupted()) {
                // половина дорожки не годится за целую: недослышанное ушло бы в
                // разговор как дослышанное. Прерывание смотрим здесь сами —
                // библиотека его не смотрит и крутила бы цикл, держа файл открытым
                log.debug("Разбор дорожки прерван — слушать не будем");
                return null;
            }
            byte[] encoded = new byte[packet.getData().remaining()];
            packet.getData().duplicate().get(encoded);
            try {
                decoder.decodeFrame(withoutAdts(encoded), buffer);
            } catch (RuntimeException e) {
                // битый кадр посреди дорожки — остальное всё ещё стоит послушать
                log.debug("Кадр звука не декодировался: {}", e.getMessage());
                continue;
            }
            if (buffer.getSampleRate() > 0) {
                // при HE-AAC декодер отдаёт удвоенную частоту, и знает он её точнее заголовка
                rate = buffer.getSampleRate();
            }
            short[] chunk = Pcm.mono(Pcm.fromBytes(buffer.getData(), ByteOrder.LITTLE_ENDIAN),
                    Math.max(1, buffer.getChannels()));
            chunks.add(chunk);
            total += chunk.length;
            if (reachedLimit(total, rate, maxSeconds)) {
                log.debug("Дорожка длиннее {} с — берём только начало", maxSeconds);
                cut = true;
                break;
            }
        }
        if (rate <= 0) {
            return null;
        }
        short[] samples = new short[total];
        int offset = 0;
        for (short[] chunk : chunks) {
            System.arraycopy(chunk, 0, samples, offset, chunk.length);
            offset += chunk.length;
        }
        return new Sound(samples, rate, cut);
    }

    /**
     * Довольно ли уже вынутых отсчётов: набралось ли запрошенное число секунд.
     * <p>
     * Частота появляется только после первого раскодированного кадра, и до неё
     * отсчётов просто нет: без этой оговорки условие делало бы истинным любое
     * число при нулевой частоте, разбор обрывался на первом же чанке, а в лог
     * писалось, что дорожка длиннее лимита.
     * <p>
     * Видно пакету: считать это учат без видео и без библиотеки.
     */
    static boolean reachedLimit(int total, int rate, int maxSeconds) {
        return rate > 0 && total >= (long) rate * maxSeconds;
    }

    /**
     * Пуста ли дорожка. Распознавание речи на тишине не молчит: оно выдаёт
     * выдуманные субтитры, и они уходят в разговор как услышанное. Проверить
     * громкость своими силами дешевле, чем потом объясняться.
     * Видно пакету: тишину учатся находить без видео и без распознавания.
     */
    static boolean silent(short[] samples) {
        for (short sample : samples) {
            // сравниваем именно так, а не через Math.abs: у крайнего отсчёта
            // модуль не помещается в short и выглядел бы тише порога
            if (sample >= SILENCE_PEAK || sample <= -SILENCE_PEAK) {
                return false;
            }
        }
        return true;
    }
}
