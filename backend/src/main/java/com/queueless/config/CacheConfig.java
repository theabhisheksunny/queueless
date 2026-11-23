package com.queueless.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_APP_SETTINGS = "appSettings";
    public static final String CACHE_SHOP_METADATA = "shopMetadata";

    @Value("${queueless.cache.app-settings-ttl-seconds:30}")
    private long appSettingsTtlSeconds;

    @Value("${queueless.cache.shop-metadata-ttl-seconds:3600}")
    private long shopMetadataTtlSeconds;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(CACHE_APP_SETTINGS, Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(appSettingsTtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build());
        cacheManager.registerCustomCache(CACHE_SHOP_METADATA, Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(shopMetadataTtlSeconds, TimeUnit.SECONDS)
                .recordStats()
                .build());
        return cacheManager;
    }
}
