package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthenticationEventApiTest {

  @Inject DataSource dataSource;

  @Test
  void successfulExchangeAndRefreshRecordOneSessionWithRequestMetadata() throws SQLException {
    int existingEvents = eventCount();
    Response exchange = VhrpAuthTestSupport.obtainValidAuthSession();
    String refreshToken = exchange.jsonPath().getString("refreshToken");

    given()
        .contentType(ContentType.JSON)
        .header("X-Forwarded-For", "127.0.0.1, 10.0.0.2, 8.8.8.8")
        .header("User-Agent", "refresh-agent")
        .body("{\"refreshToken\":\"" + refreshToken + "\"}")
        .when()
        .post("/api/auth/refresh")
        .then()
        .statusCode(200);

    List<EventRow> rows = eventsAfter(existingEvents);
    assertEquals(2, rows.size());
    assertEquals("oidc_exchange", rows.get(0).eventType());
    assertNull(rows.get(0).ipAddress());
    assertEquals("refresh", rows.get(1).eventType());
    assertEquals("8.8.8.8", rows.get(1).ipAddress());
    assertEquals("refresh-agent", rows.get(1).userAgent());
    assertEquals(rows.get(0).userId(), rows.get(1).userId());
    assertEquals(rows.get(0).tokenFamily(), rows.get(1).tokenFamily());
  }

  private int eventCount() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM authentication_events")) {
      result.next();
      return result.getInt(1);
    }
  }

  private List<EventRow> eventsAfter(int offset) throws SQLException {
    List<EventRow> rows = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT user_id, token_family, event_type, ip_address, user_agent "
                    + "FROM authentication_events ORDER BY id OFFSET "
                    + offset)) {
      while (result.next()) {
        rows.add(
            new EventRow(
                result.getLong("user_id"),
                result.getString("token_family"),
                result.getString("event_type"),
                result.getString("ip_address"),
                result.getString("user_agent")));
      }
    }
    return rows;
  }

  private record EventRow(
      long userId, String tokenFamily, String eventType, String ipAddress, String userAgent) {}
}
