package ru.rainedev.raine.telegram;

import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;

/**
 * Прокси для Telegram включается раньше всего: в сети, где Telegram
 * заблокирован, иначе клиент не дойдёт даже до кода подтверждения.
 * <p>
 * Ответ TDLib не ждём: конструктор не в том месте, чтобы висеть, а сам
 * вызов в TDLib становится в очередь наравне со всеми.
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
        TdApi.ProxyType type = switch (proxy.type()) {
            case "mtproto" -> new TdApi.ProxyTypeMtproto(proxy.secret());
            case "socks5" -> new TdApi.ProxyTypeSocks5(proxy.username(), proxy.password());
            default -> throw new IllegalStateException("неизвестный тип прокси: " + proxy.type());
        };
        TdApi.AddProxy request = new TdApi.AddProxy(
                new TdApi.Proxy(proxy.server(), proxy.port(), type), true, "");
        client.send(request).handle((added, error) -> {
            if (error != null) {
                log.error("Прокси не включился: {}", error.getMessage());
            } else {
                log.info("Прокси включён: {}://{}:{}", proxy.type(), proxy.server(), proxy.port());
            }
            return null;
        });
        log.info("Прокси {}://{}:{} поставлен в очередь — до авторизации пойдёт через него",
                proxy.type(), proxy.server(), proxy.port());
    }
}
