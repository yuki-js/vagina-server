package app.aoki.quarkuscrud;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class InvitationCodeDebugTest {

  @Test
  public void debugInvitationCodeIssue() {
    // Create a guest user
    Response authResponse = given().contentType(ContentType.JSON).post("/api/auth/guest");
    String jwtToken = authResponse.getHeader("Authorization").substring(7);
    System.out.println("JWT Token: " + jwtToken);

    // Create an event
    Response eventResponse =
        given()
            .header("Authorization", "Bearer " + jwtToken)
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/api/events")
            .then()
            .statusCode(201)
            .extract()
            .response();

    String responseBody = eventResponse.getBody().asString();
    System.out.println("Event Response Body: " + responseBody);
    System.out.println("Event Response Headers: " + eventResponse.getHeaders());

    // Check if invitationCode is present
    String invitationCode = eventResponse.jsonPath().getString("invitationCode");
    System.out.println("Invitation Code from response: " + invitationCode);
  }
}
