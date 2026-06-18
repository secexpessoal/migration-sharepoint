package org.migration.sharepoint.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.migration.sharepoint.controller.adapter.AdapterController;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.enums.TargetDb;
import org.migration.sharepoint.infra.exception.ErrorCode;
import org.migration.sharepoint.infra.exception.custom.InfrastructureException;
import org.migration.sharepoint.infra.filter.RateLimitingFilter;
import org.migration.sharepoint.infra.security.JwtAuthenticationFilter;
import org.migration.sharepoint.infra.writer.MigrationWriter;
import org.migration.sharepoint.infra.writer.MigrationWriterRegistry;
import org.migration.sharepoint.infra.writer.NativeTypeDefinition;
import org.migration.sharepoint.service.auth.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdapterController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "security.rate-limit.enabled=false")
class AdapterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MigrationWriterRegistry writerRegistry;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private RateLimitingFilter rateLimitingFilter;

    // -------------------------------------------------------------------------
    // GET /v1/adapters/{targetDb}/types
    // -------------------------------------------------------------------------

    @Test
    void shouldReturnMysqlTypes() throws Exception {
        MigrationWriter writer = mock(MigrationWriter.class);
        when(writer.canonicalMapping())
                .thenReturn(Map.of(
                        ColumnType.TEXT,
                        "TEXT",
                        ColumnType.NUMBER,
                        "BIGINT",
                        ColumnType.DECIMAL,
                        "DECIMAL(15,2)",
                        ColumnType.BOOLEAN,
                        "TINYINT(1)",
                        ColumnType.DATE,
                        "DATE",
                        ColumnType.DATETIME,
                        "DATETIME(6)"));
        when(writer.typeDefinitions())
                .thenReturn(List.of(
                        new NativeTypeDefinition("TINYINT", List.of()),
                        new NativeTypeDefinition("INT", List.of()),
                        new NativeTypeDefinition(
                                "DECIMAL",
                                List.of(
                                        new NativeTypeDefinition.ParamSpec("M", 1, 65),
                                        new NativeTypeDefinition.ParamSpec("D", 0, 30))),
                        new NativeTypeDefinition("VARCHAR", List.of(new NativeTypeDefinition.ParamSpec("N", 1, 65535))),
                        new NativeTypeDefinition("TEXT", List.of())));
        when(writerRegistry.get(TargetDb.MYSQL)).thenReturn(writer);

        mockMvc.perform(get("/v1/adapters/MYSQL/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonical").isMap())
                .andExpect(jsonPath("$.canonical.TEXT").value("TEXT"))
                .andExpect(jsonPath("$.canonical.NUMBER").value("BIGINT"))
                .andExpect(jsonPath("$.nativeTypes").isArray())
                .andExpect(jsonPath("$.nativeTypes[0].name").value("TINYINT"))
                .andExpect(jsonPath("$.nativeTypes[0].params").isArray())
                .andExpect(jsonPath("$.nativeTypes[2].name").value("DECIMAL"))
                .andExpect(jsonPath("$.nativeTypes[2].params[0].label").value("M"))
                .andExpect(jsonPath("$.nativeTypes[3].name").value("VARCHAR"))
                .andExpect(jsonPath("$.nativeTypes[3].params[0].max").value(65535));
    }

    @Test
    void shouldReturnPostgresqlTypes() throws Exception {
        MigrationWriter writer = mock(MigrationWriter.class);
        when(writer.canonicalMapping())
                .thenReturn(Map.of(
                        ColumnType.TEXT,
                        "TEXT",
                        ColumnType.NUMBER,
                        "BIGINT",
                        ColumnType.DECIMAL,
                        "NUMERIC(15,2)",
                        ColumnType.BOOLEAN,
                        "BOOLEAN",
                        ColumnType.DATE,
                        "DATE",
                        ColumnType.DATETIME,
                        "TIMESTAMP"));
        when(writer.typeDefinitions())
                .thenReturn(List.of(
                        new NativeTypeDefinition("TEXT", List.of()),
                        new NativeTypeDefinition("BIGINT", List.of()),
                        new NativeTypeDefinition(
                                "NUMERIC",
                                List.of(
                                        new NativeTypeDefinition.ParamSpec("M", 1, 1000),
                                        new NativeTypeDefinition.ParamSpec("D", 0, 1000))),
                        new NativeTypeDefinition("BOOLEAN", List.of()),
                        new NativeTypeDefinition("DATE", List.of()),
                        new NativeTypeDefinition("TIMESTAMP", List.of())));
        when(writerRegistry.get(TargetDb.POSTGRESQL)).thenReturn(writer);

        mockMvc.perform(get("/v1/adapters/POSTGRESQL/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonical.BOOLEAN").value("BOOLEAN"));
    }

    @Test
    void shouldReturnMongodbTypes() throws Exception {
        MigrationWriter writer = mock(MigrationWriter.class);
        when(writer.canonicalMapping()).thenReturn(Map.of(ColumnType.TEXT, "String"));
        when(writer.typeDefinitions())
                .thenReturn(List.of(
                        new NativeTypeDefinition("String", List.of()),
                        new NativeTypeDefinition("Int32", List.of()),
                        new NativeTypeDefinition("Int64", List.of()),
                        new NativeTypeDefinition("Double", List.of()),
                        new NativeTypeDefinition("Boolean", List.of()),
                        new NativeTypeDefinition("Date", List.of()),
                        new NativeTypeDefinition("ObjectId", List.of())));
        when(writerRegistry.get(TargetDb.MONGODB)).thenReturn(writer);

        mockMvc.perform(get("/v1/adapters/MONGODB/types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nativeTypes[0].name").value("String"))
                .andExpect(jsonPath("$.nativeTypes[0].params").isArray());
    }

    @Test
    void shouldReturn400WhenTargetDbIsInvalid() throws Exception {
        mockMvc.perform(get("/v1/adapters/ORACLE/types")).andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturn502WhenNoWriterFoundForTargetDb() throws Exception {
        when(writerRegistry.get(TargetDb.MYSQL))
                .thenThrow(new InfrastructureException(
                        ErrorCode.TARGET_DB_NOT_SUPPORTED, "Nenhum writer disponível para o banco: MYSQL"));

        mockMvc.perform(get("/v1/adapters/MYSQL/types")).andExpect(status().isBadRequest());
    }
}
