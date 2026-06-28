package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class VoiceAgentsApiTest {

  @Test
  void listVoiceAgentsReturnsPublicRegistryMetadataOnly() {
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/voice-agents")
        .then()
        .statusCode(200)
        .body("id", containsInAnyOrder("voice-agent-prod", "voice-agent-prod-cc"))
        .body("displayName", containsInAnyOrder("voice-agent-prod", "voice-agent-prod-cc"))
        .body("isDefault", hasItem(true))
        .body("findAll { it.isDefault == true }", hasSize(1))
        .body("$", everyItem(not(hasKey("provider"))))
        .body("$", everyItem(not(hasKey("baseUrl"))))
        .body("$", everyItem(not(hasKey("apiKey"))))
        .body("$", everyItem(not(hasKey("model"))));
  }
}
