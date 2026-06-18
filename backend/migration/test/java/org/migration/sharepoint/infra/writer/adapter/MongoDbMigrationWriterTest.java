package org.migration.sharepoint.infra.writer.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import org.migration.sharepoint.data.enums.ColumnType;
import org.migration.sharepoint.data.enums.TargetDb;
import org.migration.sharepoint.data.model.FieldMapping;

class MongoDbMigrationWriterTest {

    private final MongoDbMigrationWriter writer = new MongoDbMigrationWriter(null);

    @Test
    void shouldSupportOnlyMongodb() {
        assertThat(writer.supports(TargetDb.MONGODB)).isTrue();
        assertThat(writer.supports(TargetDb.MYSQL)).isFalse();
    }

    @Test
    void shouldExposeMongoCanonicalMappingAndNativeTypes() {
        assertThat(writer.canonicalMapping()).containsEntry(ColumnType.TEXT, "String");
        assertThat(writer.canonicalMapping()).containsEntry(ColumnType.DECIMAL, "Decimal128");
        assertThat(writer.typeDefinitions()).extracting("name").contains("String", "ObjectId", "Array");
    }

    @Test
    void shouldConvertEmptyStringToNullForNumericAndDateTypes() {
        FieldMapping numMapping = FieldMapping.builder().type(ColumnType.NUMBER).build();
        FieldMapping dateMapping = FieldMapping.builder().type(ColumnType.DATE).build();
        FieldMapping textMapping = FieldMapping.builder().type(ColumnType.TEXT).build();

        assertThat(writer.convert("", numMapping)).isNull();
        assertThat(writer.convert("  ", numMapping)).isNull();
        assertThat(writer.convert("", dateMapping)).isNull();
        assertThat(writer.convert("", textMapping)).isEqualTo("");
    }

    @Test
    void shouldConvertCanonicalTypesToBsonFriendlyValues() {
        assertThat(writer.convert(
                        "10", FieldMapping.builder().type(ColumnType.INTEGER).build()))
                .isEqualTo(10);
        assertThat(writer.convert(
                        "10", FieldMapping.builder().type(ColumnType.NUMBER).build()))
                .isEqualTo(10L);
        assertThat(writer.convert(
                        "true", FieldMapping.builder().type(ColumnType.BOOLEAN).build()))
                .isEqualTo(true);
        assertThat(writer.convert(
                        "2026-06-17T10:15:30Z",
                        FieldMapping.builder().type(ColumnType.DATETIME).build()))
                .isEqualTo(java.util.Date.from(Instant.parse("2026-06-17T10:15:30Z")));
    }

    @Test
    void shouldRespectNativeTypeOverCanonicalType() {
        FieldMapping decimalMapping = FieldMapping.builder()
                .type(ColumnType.TEXT)
                .nativeType("Decimal128")
                .build();

        assertThat(writer.resolveType(decimalMapping)).isEqualTo("Decimal128");
        assertThat(writer.convert("15.20", decimalMapping)).isEqualTo(Decimal128.parse("15.20"));
    }

    @Test
    void shouldCreateDocumentOnlyWithMappedColumns() {
        Map<String, FieldMapping> types = Map.of(
                "title",
                        FieldMapping.builder()
                                .column("title")
                                .type(ColumnType.TEXT)
                                .build(),
                "count",
                        FieldMapping.builder()
                                .column("count")
                                .type(ColumnType.NUMBER)
                                .build());

        Document document = writer.toDocument(Map.of("_sp_id", "1", "title", "Hello", "count", "7"), types);

        assertThat(document).containsEntry("title", "Hello").containsEntry("count", 7L);
        assertThat(document).doesNotContainKey("_sp_id");
    }

    @Test
    void shouldDetectExistingCompoundUniqueIndexesFromMongo() {
        List<Document> indexes = List.of(
                new Document("name", "_id_")
                        .append("key", new Document("_id", 1))
                        .append("unique", true),
                new Document("name", "expedients_expedientTypeId_number_key")
                        .append("key", new Document("expedientTypeId", 1).append("number", 1))
                        .append("unique", true),
                new Document("name", "expedients_synchronizedAt_idx").append("key", new Document("synchronizedAt", 1)));

        assertThat(writer.uniqueIndexDefinitionsFromDocuments(indexes))
                .extracting(MongoDbMigrationWriter.UniqueIndexDefinition::fields)
                .containsExactly(List.of("expedientTypeId", "number"));
    }
}
