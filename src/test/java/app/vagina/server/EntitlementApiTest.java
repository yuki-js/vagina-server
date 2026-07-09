package app.vagina.server;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.vagina.server.entity.EntitlementGrantSource;
import app.vagina.server.service.EntitlementService;
import app.vagina.server.support.HarigataOidcMockServerResource;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
class EntitlementApiTest implements HarigataOidcMockServerResource.HarigataOidcMockServerAware {

  @Inject EntitlementService entitlementService;

  private WireMockServer harigata;

  @Override
  public void setWireMockServer(WireMockServer wireMockServer) {
    this.harigata = wireMockServer;
  }

  @Test
  void meReturnsEmptyEntitlementsByDefault() {
    stubHarigataUser("entitlement-empty-subject", "entitlement-empty");
    String token = VhrpAuthTestSupport.obtainValidJwt();

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("entitlements", empty());
  }

  @Test
  void meReturnsOnlyActiveEffectiveEntitlements() {
    stubHarigataUser("entitlement-active-subject", "entitlement-active");
    String token = VhrpAuthTestSupport.obtainValidJwt();
    Long userId = currentUserId(token);
    LocalDateTime now = LocalDateTime.now();

    entitlementService.ensureDefinition("premium.voice", "Premium Voice", null);
    entitlementService.ensureDefinition("future.privilege", "Future Privilege", null);
    entitlementService.ensureDefinition("expired.privilege", "Expired Privilege", null);
    entitlementService.ensureDefinition("revoked.privilege", "Revoked Privilege", null);

    entitlementService.grantEntitlement(
        userId,
        "premium.voice",
        EntitlementGrantSource.MANUAL,
        now.minusMinutes(5),
        now.plusHours(1),
        "test active entitlement");
    entitlementService.grantEntitlement(
        userId,
        "future.privilege",
        EntitlementGrantSource.MANUAL,
        now.plusHours(1),
        now.plusHours(2),
        "test future entitlement");
    entitlementService.grantEntitlement(
        userId,
        "expired.privilege",
        EntitlementGrantSource.MANUAL,
        now.minusHours(2),
        now.minusHours(1),
        "test expired entitlement");
    entitlementService.grantEntitlement(
        userId,
        "revoked.privilege",
        EntitlementGrantSource.MANUAL,
        now.minusMinutes(5),
        now.plusHours(1),
        "test revoked entitlement");
    assertTrue(entitlementService.revokeEntitlement(userId, "revoked.privilege", "test revoke"));

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("entitlements", contains("premium.voice"));
  }

  @Test
  void duplicateActiveGrantsCollapseToOneEffectiveEntitlementKey() {
    stubHarigataUser("entitlement-duplicate-subject", "entitlement-duplicate");
    String token = VhrpAuthTestSupport.obtainValidJwt();
    Long userId = currentUserId(token);

    entitlementService.ensureDefinition("privileged.support", "Privileged Support", null);
    entitlementService.grantEntitlement(
        userId,
        "privileged.support",
        EntitlementGrantSource.MANUAL,
        null,
        null,
        "first duplicate grant");
    entitlementService.grantEntitlement(
        userId,
        "privileged.support",
        EntitlementGrantSource.SYSTEM,
        null,
        null,
        "second duplicate grant");

    given()
        .auth()
        .oauth2(token)
        .accept(ContentType.JSON)
        .when()
        .get("/api/me")
        .then()
        .statusCode(200)
        .body("entitlements", contains("privileged.support"));
  }

  @Test
  void revokedEntitlementCanBeGrantedAgain() {
    stubHarigataUser("entitlement-regrant-subject", "entitlement-regrant");
    String token = VhrpAuthTestSupport.obtainValidJwt();
    Long userId = currentUserId(token);

    entitlementService.ensureDefinition("manual.regrant", "Manual Regrant", null);
    entitlementService.grantEntitlement(
        userId, "manual.regrant", EntitlementGrantSource.MANUAL, null, null, "initial grant");
    assertTrue(entitlementService.revokeEntitlement(userId, "manual.regrant", "test revoke"));
    assertFalse(entitlementService.hasActiveEntitlement(userId, "manual.regrant"));

    entitlementService.grantEntitlement(
        userId, "manual.regrant", EntitlementGrantSource.MANUAL, null, null, "second grant");

    assertTrue(entitlementService.hasActiveEntitlement(userId, "manual.regrant"));
  }

  private void stubHarigataUser(String subject, String login) {
    harigata.stubFor(
        get(urlPathEqualTo("/userinfo"))
            .atPriority(1)
            .withHeader(
                "Authorization",
                equalTo("Bearer " + HarigataOidcMockServerResource.DEFAULT_ACCESS_TOKEN))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(userInfoJson(subject, login))));
  }

  private String userInfoJson(String subject, String login) {
    return """
        {
          "sub": "%s",
          "preferred_username": "%s",
          "name": "%s",
          "picture": "https://harigata.example.test/%s.png",
          "email": "%s@example.test",
          "email_verified": true
        }
        """
        .formatted(subject, login, login, login, login);
  }

  private Long currentUserId(String token) {
    Response response =
        given()
            .auth()
            .oauth2(token)
            .accept(ContentType.JSON)
            .when()
            .get("/api/me")
            .then()
            .statusCode(200)
            .extract()
            .response();
    return Long.valueOf(response.jsonPath().getString("id"));
  }
}
