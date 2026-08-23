package com.ld.poetry.im.websocket;

import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TioUtil {

    private static TioWebsocketStarter tioWebsocketStarter;

    public static void buildTio() {
        TioWebsocketStarter websocketStarter = null;
        try {
            websocketStarter = SpringUtil.getBean(TioWebsocketStarter.class);
        } catch (Exception e) {
            log.info("IM WebSocket 未启用或 Bean 尚不可用：{}", e.getMessage());
        }
        TioUtil.tioWebsocketStarter = websocketStarter;
    }

    public static TioWebsocketStarter getTio() {
        return TioUtil.tioWebsocketStarter;
    }
}
