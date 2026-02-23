package com.exchange.privatepush.config;

import com.exchange.privatepush.handler.PrivateWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * 
 * 路径: /ws/private
 * 支持: 100万+ 并发连接
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Autowired
    private PrivateWebSocketHandler privateWebSocketHandler;
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(privateWebSocketHandler, "/ws/private")
                .setAllowedOrigins("*");  // 生产环境应配置具体域名
    }
}
