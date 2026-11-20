package com.keplura.server.websearch;

import com.keplura.server.admin.SecretCipher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSearchSettingsTest {
    @Test void keylessSettingsCanBeSavedRepeatedlyAndSecretsRemainWriteOnly() {
        var ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:web-settings-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/migration/V21__web_search_settings.sql")).execute(ds);
        var cipher = mock(SecretCipher.class);
        when(cipher.available()).thenReturn(true);
        when(cipher.encrypt("test-key")).thenReturn("encrypted-test-key");
        when(cipher.decrypt("encrypted-test-key")).thenReturn("test-key");
        var jdbc = new JdbcTemplate(ds);
        var service = new WebSearchService("none", "", "", 5, "", 1);
        service.setStore(jdbc, cipher);
        var controller = new WebSearchAdminController(service, jdbc, cipher);
        for (String key : new String[]{null, "test-key", null, "", null}) {
            var response = controller.put(new WebSearchAdminController.Input("searxng", "http://localhost", key, 5, ""));
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().toString()).doesNotContain("test-key");
        }
        assertThat(controller.get().hasApiKey()).isFalse();
        when(cipher.available()).thenReturn(false);
        assertThat(controller.put(new WebSearchAdminController.Input("searxng", "http://localhost", "test-key", 5, ""))
                .getStatusCode().value()).isEqualTo(400);
    }
}
