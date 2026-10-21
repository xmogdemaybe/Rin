package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.config.Config;

class TelegramProxyTest {

    private static Config.TelegramProxy proxy(String type, String secret, String user, String password) {
        return new Config.TelegramProxy(type, "proxy.example", 443, secret, user, password);
    }

    @Test
    void mtprotoCarriesTheSecret() {
        TdApi.AddProxy request = TelegramProxy.request(proxy("mtproto", "aa:bb:cc", "", ""));

        assertEquals("proxy.example", request.proxy.server);
        assertEquals(443, request.proxy.port);
        assertEquals("aa:bb:cc",
                assertInstanceOf(TdApi.ProxyTypeMtproto.class, request.proxy.type).secret);
        assertTrue(request.enable, "прокси включается сразу, а не лежит про запас");
    }

    @Test
    void socks5CarriesLoginAndPassword() {
        TdApi.AddProxy request = TelegramProxy.request(proxy("socks5", "", "user", "pass"));

        TdApi.ProxyTypeSocks5 type = assertInstanceOf(TdApi.ProxyTypeSocks5.class, request.proxy.type);
        assertEquals("user", type.username);
        assertEquals("pass", type.password);
    }

    @Test
    void uppercaseTypeIsUnderstood() {
        // тип приводят к нижнему регистру сами настройки: запрос обязан это
        // учитывать, а не падать на «MTPROTO»
        TdApi.AddProxy request = TelegramProxy.request(proxy("MTPROTO", "aa:bb", "", ""));

        assertEquals("aa:bb", assertInstanceOf(TdApi.ProxyTypeMtproto.class, request.proxy.type).secret);
    }
}
