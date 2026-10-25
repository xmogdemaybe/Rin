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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
     * Дальше этого времени звук не берём: ролик из переписки — это минуты,
     * а держать в памяти дорожку от часового фильма ни к чему.
     */
    private static final int MAX_SECONDS = 600;

    /**
     * Дольше этого разбор не идёт ни при каких условиях: на битом контейнере
     * библиотека способна уйти в вечный цикл (проверено на файле, который
     * видео только притворяется).
     */
    private static final int TIMEOUT_SECONDS = 60;

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
     * @return отсчёты одного канала или пусто, если звука нет или формат не поддался
     */
    public static Optional<Sound> sound(Path video) {
        if (!looksLikeMp4(video)) {
            log.debug("Это не MP4 — звук останется неуслышанным");
            return Optional.empty();
        }
        // разбор идёт в стороне от общего потока: чужой файл может оказаться
        // таким, что библиотека на нём зациклится, и ждать её вечно мы не станем
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "video-audio");
            // если разбор всё-таки завис, он не помешает боту остановиться
            thread.setDaemon(true);
            return thread;
        });
        Future<Optional<Sound>> parsed = worker.submit(() -> decodeTrack(video));
        try {
            return parsed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            parsed.cancel(true);
            log.debug("Разбор звука не уложился в {} с — слушать не будем", TIMEOUT_SECONDS);
            return Optional.empty();
        } catch (ExecutionException e) {
            log.debug("Звук из видео не разобрался: {}", e.getCause().getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            worker.shutdownNow();
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

    private static Optional<Sound> decodeTrack(Path video) {
        short[] samples;
        int rate;
        try (FileChannelWrapper channel = NIOUtils.readableChannel(video.toFile())) {
            Optional<Aac> track = audioTrack(MP4Demuxer.createMP4Demuxer(channel));
            if (track.isEmpty()) {
                log.debug("В видео нет разбираемой звуковой дорожки");
                return Optional.empty();
            }
            Sound sound = decode(track.get());
            if (sound == null || sound.samples().length == 0) {
                return Optional.empty();
            }
            if (silent(sound.samples())) {
                // дорожка есть, а звука в ней нет: распознавание речи на тишине
                // не молчит, а выдумывает субтитры
                log.debug("Дорожка оказалась тишиной — слушать нечего");
                return Optional.empty();
            }
            samples = sound.samples();
            rate = sound.rate();
        } catch (IOException | RuntimeException e) {
            // неподдержанный контейнер — обычное дело, разговор из-за этого не ломаем
            log.debug("Звук из видео не разобрался: {}", e.getMessage());
            return Optional.empty();
        }
        log.debug("Дорожка вынута: {} с", samples.length / rate);
        return Optional.of(new Sound(Pcm.resample(samples, rate, TARGET_RATE), TARGET_RATE));
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

    private static Sound decode(Aac audio) throws IOException {
        AbstractMP4DemuxerTrack track = audio.track();
        Decoder decoder = Decoder.create(audio.description());
        SampleBuffer buffer = new SampleBuffer();
        buffer.setBigEndian(false);

        List<short[]> chunks = new ArrayList<>();
        int total = 0;
        int rate = 0;

        Packet packet;
        while ((packet = track.nextFrame()) != null) {
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
            if (total >= (long) rate * MAX_SECONDS) {
                log.debug("Дорожка длиннее {} с — слушаем только начало", MAX_SECONDS);
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
        return new Sound(samples, rate);
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
