package com.eventplatform;

import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.eventplatform.config.MybatisConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisCompatibilityTest {
    @Test
    void paginationPluginAndSqlParserLoadTogether() throws Exception {
        assertThat(new MybatisConfig().mybatisPlusInterceptor().getInterceptors()).hasSize(1);
        assertThat(JsqlParserGlobal.parse("SELECT id FROM tb_shop ORDER BY id LIMIT 5").toString())
                .contains("tb_shop", "LIMIT 5");
    }
}
