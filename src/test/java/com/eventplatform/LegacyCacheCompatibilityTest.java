package com.eventplatform;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.eventplatform.entity.Shop;
import com.eventplatform.utils.RedisData;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyCacheCompatibilityTest {
    @Test
    void readsExistingLogicalExpiryCacheAndRoundTripsJavaTime() {
        String existing = "{\"expireTime\":\"2026-09-03T12:30:00\","
                + "\"data\":{\"id\":1,\"name\":\"shop\"}}";
        RedisData cached = JSONUtil.toBean(existing, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) cached.getData(), Shop.class);

        assertThat(shop.getId()).isEqualTo(1L);
        assertThat(shop.getName()).isEqualTo("shop");
        assertThat(cached.getExpireTime()).isEqualTo(LocalDateTime.of(2026, 9, 3, 12, 30));

        cached.setData(shop);
        RedisData restored = JSONUtil.toBean(JSONUtil.toJsonStr(cached), RedisData.class);

        assertThat(restored.getExpireTime()).isEqualTo(cached.getExpireTime());
        assertThat(JSONUtil.toBean((JSONObject) restored.getData(), Shop.class).getId())
                .isEqualTo(shop.getId());
    }
}
