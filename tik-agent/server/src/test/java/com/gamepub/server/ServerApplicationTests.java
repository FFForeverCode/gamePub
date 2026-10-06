package com.gamepub.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.gamepub.server.support.MySqlIntegrationTest;

@SpringBootTest
class ServerApplicationTests extends MySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndMigratesChatTables() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = database()
                  and table_name in ('conversations', 'messages')
                """, Integer.class);

        assertThat(count).isEqualTo(2);
    }

}
