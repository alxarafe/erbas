package com.alxarafe.erbas.adapter.http;

import com.alxarafe.erbas.auth.infrastructure.AuthenticationConfiguration;
import com.alxarafe.erbas.auth.infrastructure.JdbcAuthenticationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(AuthenticationConfiguration.class)
class HealthControllerTests {

    @MockitoBean
    private JdbcAuthenticationStore store;

    @Autowired
    private MockMvc mvc;

    @Test
    void returnsOnlyProcessLivenessWithoutInfrastructure() throws Exception {
        mvc.perform(get("/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":\"ok\"}", JsonCompareMode.STRICT));
    }
}
