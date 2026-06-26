package org.migration.sharepoint.infra.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "security.rate-limit.enabled=false")
class ServerSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldDenySwaggerApiDocsWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isForbidden());
    }

    @Test
    void shouldDenySwaggerUiAssetsWhenUnauthenticated() throws Exception {
        mockMvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isForbidden());
    }

    @Test
    void shouldDenySwaggerApiDocsForNonAdmin() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("X-Forwarded-User", "viewer")
                        .header("X-Forwarded-Email", "viewer@localhost")
                        .header("X-Forwarded-Roles", "ROLE_USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowSwaggerApiDocsForAdmin() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("X-Forwarded-User", "admin")
                        .header("X-Forwarded-Email", "admin@localhost")
                        .header("X-Forwarded-Roles", "ROLE_ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowSwaggerApiDocsForAdminWithoutRolePrefix() throws Exception {
        mockMvc.perform(get("/v3/api-docs")
                        .header("X-Forwarded-User", "admin")
                        .header("X-Forwarded-Email", "admin@localhost")
                        .header("X-Forwarded-Roles", "ADMIN, MANAGER, USER"))
                .andExpect(status().isOk());
    }
}
