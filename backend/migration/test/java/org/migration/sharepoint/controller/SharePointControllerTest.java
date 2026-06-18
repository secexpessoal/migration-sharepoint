package org.migration.sharepoint.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.migration.sharepoint.controller.sharepoint.SharePointController;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.custom.BadRequestException;
import org.migration.sharepoint.infra.exception.custom.InfrastructureException;
import org.migration.sharepoint.infra.filter.RateLimitingFilter;
import org.migration.sharepoint.infra.graph.GraphClient;
import org.migration.sharepoint.infra.graph.GraphClient.SharePointResolveResult;
import org.migration.sharepoint.infra.security.JwtAuthenticationFilter;
import org.migration.sharepoint.service.auth.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SharePointController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "security.rate-limit.enabled=false")
class SharePointControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GraphClient graphClient;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private RateLimitingFilter rateLimitingFilter;

    // -------------------------------------------------------------------------
    // POST /v1/sharepoint/resolve
    // -------------------------------------------------------------------------

    @Test
    void shouldResolveSharePointUrlAndReturn200() throws Exception {
        SharePointResolveResult result = new SharePointResolveResult(
                "site-id-abc", "list-id-xyz", List.of("Author", "Created", "Modified", "Title"));

        when(graphClient.resolveSharePointUrl(anyString())).thenReturn(result);

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/MySite/Lists/MyList/AllItems.aspx"}
				"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siteId").value("site-id-abc"))
                .andExpect(jsonPath("$.listId").value("list-id-xyz"))
                .andExpect(jsonPath("$.columns").isArray())
                .andExpect(jsonPath("$.columns.length()").value(4))
                .andExpect(jsonPath("$.columns[0]").value("Author"));
    }

    @Test
    void shouldReturn400WhenUrlIsBlank() throws Exception {
        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":""}
				"""))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(graphClient);
    }

    @Test
    void shouldReturn400WhenUrlIsMissing() throws Exception {
        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn400WhenUrlDoesNotContainListsSegment() throws Exception {
        when(graphClient.resolveSharePointUrl(anyString()))
                .thenThrow(new BadRequestException(
                        ErrorCode.SHAREPOINT_INVALID_URL,
                        "URL não contém /Lists/ — forneça a URL completa da lista SharePoint"));

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/MySite"}
				"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn401WhenAzureCredentialsAreInvalid() throws Exception {
        when(graphClient.resolveSharePointUrl(anyString()))
                .thenThrow(new InfrastructureException(
                        ErrorCode.GRAPH_UNAUTHORIZED,
                        "Token rejeitado — verifique GRAPH_CLIENT_ID e GRAPH_CLIENT_SECRET"));

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/MySite/Lists/MyList/AllItems.aspx"}
				"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403WhenNoPermissionToAccessSiteOrList() throws Exception {
        when(graphClient.resolveSharePointUrl(anyString()))
                .thenThrow(
                        new InfrastructureException(ErrorCode.GRAPH_FORBIDDEN, "Sem permissão para acessar o recurso"));

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/Restricted/Lists/SecretList/AllItems.aspx"}
				"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn404WhenSiteOrListNotFound() throws Exception {
        when(graphClient.resolveSharePointUrl(anyString()))
                .thenThrow(new InfrastructureException(
                        ErrorCode.GRAPH_SITE_OR_LIST_NOT_FOUND, "Site ou lista não encontrado"));

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/Missing/Lists/Missing/AllItems.aspx"}
				"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturn504WhenGraphApiTimesOut() throws Exception {
        when(graphClient.resolveSharePointUrl(anyString()))
                .thenThrow(new InfrastructureException(ErrorCode.GRAPH_TIMEOUT, "Timeout na Graph API"));

        mockMvc.perform(post("/v1/sharepoint/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
				{"url":"https://tenant.sharepoint.com/sites/MySite/Lists/MyList/AllItems.aspx"}
				"""))
                .andExpect(status().isGatewayTimeout());
    }
}
