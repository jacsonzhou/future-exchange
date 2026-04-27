package com.exchange.oms.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.exchange.common.core.enums.OrderStatus;
import com.exchange.common.core.enums.Side;
import com.exchange.oms.config.typehandler.OrderStatusTypeHandler;
import com.exchange.oms.config.typehandler.SideTypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis Plus配置
 */
@Configuration
@MapperScan("com.exchange.oms.mapper")
public class MybatisPlusConfig {
    
    /**
     * MyBatis Plus拦截器
     * 
     * 包含：
     * 1. 分页插件
     * 2. 乐观锁插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        
        // 乐观锁插件
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        
        return interceptor;
    }
    
    /**
     * 注册自定义 TypeHandler
     */
    @Bean
    public org.apache.ibatis.session.Configuration mybatisConfiguration() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        
        // 注册枚举 TypeHandler
        typeHandlerRegistry.register(Side.class, SideTypeHandler.class);
        typeHandlerRegistry.register(OrderStatus.class, OrderStatusTypeHandler.class);
        
        return configuration;
    }
}






