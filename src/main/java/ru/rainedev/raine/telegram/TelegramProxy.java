package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Прокси для Telegram включается до первого нашего запроса: в сети, где
 * Telegram заблокирован, иначе не будет даже кода подтверждения.
 * <p>
 * Раньше нельзя: билдер tdlight прокси не принимает, а клиент появляется
 * только после {@code build()}. Самое первое рукопожатие с серверами поэтому
 * идёт без прокси — дальше TDLib переподключается уже через него, и
 * авторизация проходит по прокси. Ответ TDLib не ждём: конструктор не в том
 * месте, чтобы висеть, а сам вызов становится в ту же очередь, что и все
 * наши обращения, — очередь сохраняет порядок.
 */
public final class TelegramProxy {

    private static final Logger log = LoggerFactory.getLogger(TelegramProxy.class);

    private TelegramProxy() {
    }

    /** Включает прокси, если тот настроен; без настроек ничего не делает. */
    public static void setup(SimpleTelegramClient client, Config.TelegramProxy proxy) {
        if (!proxy.enabled()) {
            return;
        }
        client.send(request(proxy)).handle((added, error) -> {
            if (error != null) {
                log.error("Прокси не включился: {}", error.getMessage());
            }
            return null;
        });
        log.info("Прокси {}://{}:{} включается — до авторизации пойдёт через него",
                proxy.type(), proxy.server(), proxy.port());
    }

    /** Запрос TDLib: адрес, порт и тип прокси ровно так, как заданы в настройках. */
    static TdApi.AddProxy request(Config.TelegramProxy proxy) {
        TdApi.ProxyType type = switch (proxy.type()) {
            case "mtproto" -> new TdApi.ProxyTypeMtproto(proxy.secret());
            case "socks5" -> new TdApi.ProxyTypeSocks5(proxy.username(), proxy.password());
            default -> throw new IllegalStateException("неизвестный тип прокси: " + proxy.type());
        };
        return new TdApi.AddProxy(new TdApi.Proxy(proxy.server(), proxy.port(), type), true, "");
    }
}
