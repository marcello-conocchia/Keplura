package com.keplura.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Hermetic datasource (ADMIN-KI) — see ApiIntegrationTest for the why:
        // never boot onto the shared target/keplura-dev file DB.
        "spring.datasource.url=jdbc:h2:mem:request-logging-test;DB_CLOSE_DELAY=-1"
})
class RequestLoggingTest {

    @Autowired
    private MockMvc mockMvc;

    private Logger interceptorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachListAppender() {
        interceptorLogger = (Logger) LoggerFactory.getLogger(RequestLoggingInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
    }

    @AfterEach
    void detachListAppender() {
        interceptorLogger.detachAppender(appender);
    }

    private List<ILoggingEvent> loggedInfoEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
    }

    @Test
    void apiRequestsAreLoggedAtInfoWithMethodPathStatus() throws Exception {
        mockMvc.perform(get("/api/v1/system/info")).andExpect(status().isOk());

        List<ILoggingEvent> events = loggedInfoEvents();
        assertThat(events).hasSize(1);
        String line = events.get(0).getFormattedMessage();
        assertThat(line).startsWith("GET /api/v1/system/info -> 200 (")
                .endsWith("ms)");
    }

    @Test
    void queryParametersAreNeverLogged() throws Exception {
        mockMvc.perform(get("/api/v1/system/info?token=super-secret&user=alice"))
                .andExpect(status().isOk());

        List<ILoggingEvent> events = loggedInfoEvents();
        assertThat(events).hasSize(1);
        String line = events.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("super-secret").doesNotContain("user=alice")
                .doesNotContain("?");
    }

    @Test
    void healthProbeIsNotLogged() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());

        assertThat(loggedInfoEvents()).isEmpty();
    }
}
