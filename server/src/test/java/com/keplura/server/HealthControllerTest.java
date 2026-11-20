package com.keplura.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Hermetic datasource (ADMIN-KI) — see ApiIntegrationTest for the why:
        // never boot onto the shared target/keplura-dev file DB.
        "spring.datasource.url=jdbc:h2:mem:health-controller-test;DB_CLOSE_DELAY=-1"
})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void livenessEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessResponseIsMinimalSingleFieldJson() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(
                        com.jayway.jsonpath.JsonPath.parse(result.getResponse().getContentAsString())
                                .read("$", java.util.Map.class))
                        .containsOnlyKeys("status"));
    }

    @Test
    void healthControllerUnitReturnsUp() {
        assertThat(new HealthController().health().status()).isEqualTo("UP");
    }
}
