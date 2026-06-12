package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Smoke test to verify the application can start successfully. Tests basic health endpoint to
 * ensure Quarkus boots correctly.
 */
@QuarkusTest
public class ApplicationStartupTest {

  @Test
  public void testHealthEndpointAccessible() {
    given().when().get("/healthz").then().statusCode(200);
  }
}
