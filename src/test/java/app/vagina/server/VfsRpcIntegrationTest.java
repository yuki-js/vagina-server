package app.vagina.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import app.vagina.server.support.HarigataOidcMockServerResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration scenarios for the persisted VFS JSON-RPC API.
 *
 * <p>Actor flow:
 * 1. A human user signs in through OIDC from the client.
 * 2. The client issues persisted document operations against `/vfs/rpc`.
 * 3. The server applies write/read/list/move/delete semantics on persisted storage only.
 *
 * <p>These tests cover the storage substrate that the file browser and future
 * call-adjacent tooling rely on, while intentionally excluding active in-call file state.
 */
@QuarkusTest
@QuarkusTestResource(HarigataOidcMockServerResource.class)
@TestMethodOrder(OrderAnnotation.class)
public class VfsRpcIntegrationTest {
  private static String accessToken;

  /**
   * Actor setup: the user completes the same Harigata OIDC browser flow that the
   * client uses before any persisted VFS call can be made.
   */
  @Test
  @Order(1)
  public void setupAuthenticatedUser() {
    PkcePair pkce = issuePkcePair();
    Response startResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "clientType", "web",
                    "codeChallenge", pkce.codeChallenge(),
                    "codeChallengeMethod", "S256"))
            .when()
            .post("/api/auth/oidc/harigata/start")
            .then()
            .statusCode(200)
            .extract()
            .response();

    String authorizationUrl = startResponse.jsonPath().getString("authorizationUrl");

    Response authorizeResponse =
        given()
            .redirects()
            .follow(false)
            .when()
            .get(authorizationUrl)
            .then()
            .statusCode(302)
            .extract()
            .response();

    RedirectPayload redirectPayload = parseRedirectPayload(authorizeResponse.getHeader("Location"));

    Response exchangeResponse =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "code",
                    redirectPayload.code(),
                    "state",
                    redirectPayload.state(),
                    "codeVerifier",
                    pkce.codeVerifier()))
            .when()
            .post("/api/auth/oidc/harigata/exchange")
            .then()
            .statusCode(200)
            .body("accessToken", notNullValue())
            .extract()
            .response();

    accessToken = exchangeResponse.jsonPath().getString("accessToken");
    assertNotNull(accessToken);
  }

  /**
   * Scenario: the client persists a note, reopens it, browses directories, archives it,
   * and finally deletes it. This mirrors a realistic persisted-document lifecycle outside
   * the realtime active-file session.
   */
  @Test
  @Order(2)
  public void testWriteReadListMoveDeleteFlow() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "write-1",
                "method",
                "vfs.write",
                "params",
                Map.of("path", "/notes/today.md", "content", "# Today")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("jsonrpc", equalTo("2.0"))
        .body("id", equalTo("write-1"))
        .body("result.file.path", equalTo("/notes/today.md"))
        .body("result.file.content", equalTo("# Today"))
        .body("error", nullValue());

    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "read-1",
                "method",
                "vfs.read",
                "params",
                Map.of("path", "/notes/today.md")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result.file.path", equalTo("/notes/today.md"))
        .body("result.file.content", equalTo("# Today"))
        .body("error", nullValue());

    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "list-root",
                "method",
                "vfs.list",
                "params",
                Map.of("path", "/", "recursive", false)))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result.entries", hasSize(1))
        .body("result.entries[0]", equalTo("notes/"));

    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "list-recursive",
                "method",
                "vfs.list",
                "params",
                Map.of("path", "/", "recursive", true)))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result.entries", hasSize(1))
        .body("result.entries[0]", equalTo("notes/today.md"));

    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "move-1",
                "method",
                "vfs.move",
                "params",
                Map.of("fromPath", "/notes/today.md", "toPath", "/archive/today.md")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result.fromPath", equalTo("/notes/today.md"))
        .body("result.toPath", equalTo("/archive/today.md"))
        .body("error", nullValue());

    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "delete-1",
                "method",
                "vfs.delete",
                "params",
                Map.of("path", "/archive/today.md")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result.path", equalTo("/archive/today.md"))
        .body("error", nullValue());
  }

  /**
   * Scenario: the client asks for a persisted file path that has never been created.
   * The JSON-RPC envelope is valid, so transport stays 200 while the domain failure
   * is returned in the JSON-RPC error object.
   */
  @Test
  @Order(3)
  public void testReadMissingFileReturnsJsonRpcError() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "read-missing",
                "method",
                "vfs.read",
                "params",
                Map.of("path", "/missing/file.md")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result", nullValue())
        .body("error.code", equalTo(-32004))
        .body("error.message", equalTo("File not found"))
        .body("error.data.path", equalTo("/missing/file.md"));
  }

  /**
   * Scenario: a buggy or malicious client tries to write into a reserved system path.
   * The request is still valid JSON-RPC, but domain validation must reject the path.
   */
  @Test
  @Order(4)
  public void testReservedPathRejectedAsJsonRpcInvalidParams() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "reserved-path",
                "method",
                "vfs.write",
                "params",
                Map.of("path", "/system/config.json", "content", "{}")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result", nullValue())
        .body("error.code", equalTo(-32602))
        .body("error.message", equalTo("Access denied: reserved path"))
        .body("error.data.path", equalTo("/system/config.json"));
  }

  /**
   * Scenario: the client sends a syntactically valid JSON-RPC request for move, but omits
   * required command data. The server must report invalid params without collapsing transport.
   */
  @Test
  @Order(5)
  public void testMalformedParamsStayJsonRpcAndDoNotCrashTransport() {
    given()
        .header("Authorization", "Bearer " + accessToken)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "bad-move",
                "method",
                "vfs.move",
                "params",
                Map.of("fromPath", "/only-one-path")))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(200)
        .body("result", nullValue())
        .body("error.code", equalTo(-32602))
        .body("error.message", equalTo("Missing or empty params.toPath"))
        .body("error.data.method", equalTo("vfs.move"))
        .body("error.data.fromPath", equalTo("/only-one-path"));
  }

  /**
   * Scenario: the client forgets to attach a bearer token. This is an authentication failure,
   * so it must surface as HTTP 401 instead of a JSON-RPC domain error.
   */
  @Test
  @Order(6)
  public void testUnauthenticatedRpcCallIsStillHttp401() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                "no-auth",
                "method",
                "vfs.list",
                "params",
                Map.of("path", "/", "recursive", false)))
        .when()
        .post("/api/vfs/rpc")
        .then()
        .statusCode(401)
        .body("message", equalTo("No JWT token found"));
  }

  private RedirectPayload parseRedirectPayload(String locationHeader) {
    String decodedLocation = URLDecoder.decode(locationHeader, StandardCharsets.UTF_8);
    URI redirectedUri = URI.create(decodedLocation);
    Map<String, String> queryParams = parseQueryParams(redirectedUri.getRawQuery());
    return new RedirectPayload(decodedLocation, queryParams.get("code"), queryParams.get("state"));
  }

  private Map<String, String> parseQueryParams(String rawQuery) {
    Map<String, String> values = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isBlank()) {
      return values;
    }

    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
      String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
      values.put(key, value);
    }
    return values;
  }

  private record RedirectPayload(String location, String code, String state) {}

  private PkcePair issuePkcePair() {
    String verifier = "test-verifier-" + UUID.randomUUID();
    return new PkcePair(verifier, s256(verifier));
  }

  private String s256(String verifier) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private record PkcePair(String codeVerifier, String codeChallenge) {}
}
