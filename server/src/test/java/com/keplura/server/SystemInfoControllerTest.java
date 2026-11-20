package com.keplura.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Hermetic datasource (ADMIN-KI) — see ApiIntegrationTest for the why:
        // never boot onto the shared target/keplura-dev file DB.
        "spring.datasource.url=jdbc:h2:mem:system-info-test;DB_CLOSE_DELAY=-1"
})
class SystemInfoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getSystemInfoReturnsProductAndVersions() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("Keplura Server"))
                .andExpect(jsonPath("$.serverVersion").value("0.1.0"))
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                // M4c: closed-vocabulary provider state; nothing else about
                // the provider (URL, key, model id) may ever appear.
                .andExpect(jsonPath("$.model").value("none"))
                .andExpect(jsonPath("$.*", org.hamcrest.Matchers.hasSize(4)));
    }

    @Test
    void getSystemInfoAllowsDesktopWebviewOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/system/info")
                        .header("Origin", "http://localhost:1420"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }
}
