package app.vagina.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for previous agent mistakes in metadata schema ownership and reserved-column
 * usage. Do not delete, disable, skip, narrow, weaken, or remove this test from the verification
 * path in order to make a change pass. Update this test only when the user has explicitly approved
 * a metadata-policy change, and then update the policy, migration, and this guard together.
 */
class MetadataColumnSchemaTest {
  private static final String MIGRATION_RESOURCE = "db/migration/V1__Initial_schema.sql";
  private static final List<String> USER_BOUND_TABLES =
      List.of(
          "users",
          "user_entitlement_grants",
          "authn_providers",
          "refresh_tokens",
          "speed_dials",
          "text_agents",
          "call_sessions");
  private static final List<String> SYSTEM_METADATA_ONLY_TABLES =
      List.of("entitlement_definitions", "oauth_login_attempts");
  private static final List<String> METADATA_FREE_AUDIT_TABLES =
      List.of("authentication_events");

  @Test
  void metadataColumnsFollowTableOwnership() throws IOException {
    String migration = readMigration();
    Set<String> classifiedTables = new HashSet<>(USER_BOUND_TABLES);
    assertEquals(
        USER_BOUND_TABLES.size(),
        classifiedTables.size(),
        "User-bound metadata table classification contains duplicates");
    for (String table : SYSTEM_METADATA_ONLY_TABLES) {
      assertTrue(
          classifiedTables.add(table),
          table + " must belong to exactly one metadata ownership classification");
    }
    for (String table : METADATA_FREE_AUDIT_TABLES) {
      assertTrue(
          classifiedTables.add(table),
          table + " must belong to exactly one metadata ownership classification");
    }
    assertEquals(
        allTables(migration),
        classifiedTables,
        "Every V1 table must belong to exactly one metadata ownership classification");

    for (String table : USER_BOUND_TABLES) {
      String tableBody = tableBody(migration, table);
      assertNullableJsonbColumn(table, tableBody, "usermeta");
      assertNullableJsonbColumn(table, tableBody, "sysmeta");
    }

    for (String table : SYSTEM_METADATA_ONLY_TABLES) {
      String tableBody = tableBody(migration, table);
      assertNullableJsonbColumn(table, tableBody, "sysmeta");
      assertEquals(
          0,
          countColumn(tableBody, "usermeta"),
          table + " is not bound to or owned by a concrete user");
    }

    for (String table : METADATA_FREE_AUDIT_TABLES) {
      String tableBody = tableBody(migration, table);
      assertEquals(0, countColumn(tableBody, "usermeta"), table + " must remain metadata-free");
      assertEquals(0, countColumn(tableBody, "sysmeta"), table + " must remain metadata-free");
    }
  }

  @Test
  void activeJavaCodeDoesNotReferenceReservedMetadataColumns() throws IOException {
    Path sourceRoot = Path.of("src/main/java");
    try (var paths = Files.walk(sourceRoot)) {
      List<Path> violations =
          paths
              .filter(path -> path.toString().endsWith(".java"))
              .filter(this::referencesReservedMetadata)
              .toList();
      assertTrue(
          violations.isEmpty(),
          "Reserved metadata columns must not be exposed or accessed by active Java code: "
              + violations);
    }
  }

  private boolean referencesReservedMetadata(Path path) {
    try {
      String source = Files.readString(path, StandardCharsets.UTF_8);
      return Pattern.compile("(?i)usermeta|sysmeta").matcher(source).find();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to inspect Java source: " + path, e);
    }
  }

  private String readMigration() throws IOException {
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
      assertTrue(input != null, "Migration resource not found: " + MIGRATION_RESOURCE);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private String tableBody(String migration, String table) {
    Pattern pattern =
        Pattern.compile("(?is)CREATE\\s+TABLE\\s+" + Pattern.quote(table) + "\\s*\\((.*?)\\n\\);");
    Matcher matcher = pattern.matcher(migration);
    assertTrue(matcher.find(), "Table not found in migration: " + table);
    return matcher.group(1);
  }

  private Set<String> allTables(String migration) {
    Pattern pattern = Pattern.compile("(?im)^\\s*CREATE\\s+TABLE\\s+([a-z][a-z0-9_]*)\\s*\\(");
    Matcher matcher = pattern.matcher(migration);
    Set<String> tables = new HashSet<>();
    while (matcher.find()) {
      assertTrue(tables.add(matcher.group(1)), "Duplicate CREATE TABLE: " + matcher.group(1));
    }
    assertTrue(!tables.isEmpty(), "No CREATE TABLE declarations found in V1 migration");
    return tables;
  }

  private void assertNullableJsonbColumn(String table, String tableBody, String column) {
    Pattern declaration =
        Pattern.compile("(?im)^\\s*" + Pattern.quote(column) + "\\s+JSONB\\s*,?\\s*$");
    assertEquals(
        1,
        countMatches(declaration, tableBody),
        table + "." + column + " must be declared exactly once as nullable JSONB");
  }

  private int countColumn(String tableBody, String column) {
    Pattern declaration = Pattern.compile("(?im)^\\s*" + Pattern.quote(column) + "\\s+");
    return countMatches(declaration, tableBody);
  }

  private int countMatches(Pattern pattern, String value) {
    int count = 0;
    Matcher matcher = pattern.matcher(value);
    while (matcher.find()) {
      count++;
    }
    return count;
  }
}
