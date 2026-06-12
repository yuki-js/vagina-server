# Chapter 5: Schema-First API Design

## The Problem with Code-First APIs

Most developers start by writing code:

```java
@RestController
public class UserController {
    
    @GetMapping("/users/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.findById(id);
    }
}
```

Then they generate API documentation as an afterthought. This leads to:
- **Inconsistent APIs** - each developer does it differently
- **Unclear contracts** - frontend doesn't know what to expect
- **Documented APIs** - documentation that contradicts actual behavior
- **Breaking changes** - changes break clients without notice

## The Schema-First Solution

With schema-first, we define the API **before** writing any code:

```
1. Write OpenAPI YAML spec
2. Generate code from spec
3. Implement the generated interface
```

This forces you to think about the API design before implementation.

## The OpenAPI Structure

Here's a simplified modular structure for this template style:

```
openapi/
├── openapi.yaml              # Source root spec that references the modular parts
├── paths/                    # API endpoints
│   ├── users.yaml
│   ├── events.yaml
│   └── friendships.yaml
└── components/
    └── schemas/              # Data models
        ├── user.yaml
        ├── event.yaml
        └── common.yaml
```

In the actual repository, this tree has grown with additional modules. The key idea stays the same: keep path definitions modular under `openapi/paths/`, keep schema definitions modular under `openapi/components/schemas/`, and wire them together from `openapi/openapi.yaml`.

## Example: Defining a User Endpoint

### Step 1: Define the Schema

First, define what a user contract looks like.

The following is a **simplified teaching example**, not a claim that the current repository's user schema has exactly these fields:

```yaml
# simplified example
User:
  type: object
  properties:
    id:
      type: integer
      format: int64
      description: Unique identifier
    email:
      type: string
      format: email
      description: User's email address
    accountLifecycle:
      type: string
      enum: [CREATED, ACTIVE, SUSPENDED, DELETED]
      description: Current account status
    createdAt:
      type: string
      format: date-time
      description: When the user was created
  required:
    - id
    - email
    - accountLifecycle
    - createdAt

UserPublic:
  allOf:
    - $ref: '#/User'
    - type: object
      properties:
        profile:
          $ref: '#/UserProfile'
```

### Step 2: Define the Endpoint

Then define the API endpoint:

```yaml
# simplified excerpt from a modular path file
paths:
  /api/users/{userId}:
    get:
      tags:
        - Users
      summary: Get user by ID
      description: Retrieve a public representation of the specified user.
      operationId: getUserById
      parameters:
        - name: userId
          in: path
          required: true
          description: Identifier of the user to fetch.
          schema:
            type: integer
            format: int64
      responses:
        '200':
          description: User found.
          content:
            application/json:
              schema:
                $ref: '../components/schemas/user.yaml#/UserPublic'
        '404':
          description: The user was not found.
          content:
            application/json:
              schema:
                $ref: '../components/schemas/common.yaml#/ErrorResponse'
```

When you add a **new** path in this repository, remember that editing a modular file such as `openapi/paths/users.yaml` is not enough by itself. You also need to register that path in `openapi/openapi.yaml` so the root spec exports it.

### Step 3: Compile the Spec

```bash
./gradlew compileOpenApi
```

This reads the source spec at `openapi/openapi.yaml`, resolves the modular YAML files it references, and writes the compiled single-file spec to `build/openapi-compiled/openapi.yaml`.

### Step 4: Generate Code

```bash
./gradlew generateOpenApiModels
```

This generates Java interfaces and DTOs.

In the current repository they are generated under `build/generated-src/openapi/src/gen/java/`. A simplified real example looks like this:

```java
// Generated interface (DO NOT EDIT)
@Path("/api/users/{userId}")
public interface UsersApi {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    Response getUserById(@PathParam("userId") Long userId);
}
```

```java
// Generated model (DO NOT EDIT)
public class UserPublic {
    private Long id;
    private AccountLifecycleEnum accountLifecycle;
    private OffsetDateTime createdAt;

    // getters and setters
}
```

### Step 5: Implement the Interface

Now you implement the generated interface:

```java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {

    @Inject UserService userService;

    @Override
    @Authenticated
    @GET
    @Path("/users/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserById(@PathParam("userId") Long userId) {
        return userService
            .findById(userId)
            .map(user -> Response.ok(toUserPublicResponse(user)).build())
            .orElse(
                Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("User not found"))
                    .build());
    }

    private UserPublic toUserPublicResponse(User user) {
        UserPublic response = new UserPublic();
        response.setId(user.getId());
        response.setCreatedAt(user.getCreatedAt().atOffset(ZoneOffset.UTC));
        response.setAccountLifecycle(
            UserPublic.AccountLifecycleEnum.fromValue(
                user.getAccountLifecycle().getValue()));
        return response;
    }
}
```

This specific endpoint is currently a simple Resource → Service → Mapper flow. That does not change the recommended rule elsewhere in the workbook: when an endpoint owns authorization or multi-step orchestration, prefer putting that flow in a UseCase instead of growing the Resource.

## Benefits of Schema-First

### 1. Contract First

The API is designed before implementation. Frontend and backend can agree on contracts before coding begins.

### 2. Type Safety

Generated models are type-safe. If you change a field name in YAML, the Java code won't compile until updated.

### 3. Documentation

Swagger UI automatically reflects the spec. No more outdated documentation.

### 4. Client Generation

Generate clients for JavaScript, Python, etc. from the same spec:

```bash
./gradlew generateJavascriptFetchClient
```

### 5. Contract Testing

Verify API responses match the spec:

```java
@Test
void validateApiContract() {
    given()
        .when()
        .get("/api/users/1")
        .then()
        .body("id", equalTo(1))
        .body("accountLifecycle", notNullValue());
}
```

## Exercise 5.1: Add a New Endpoint

Your task: Add a `PATCH /api/users/{userId}/account-lifecycle` endpoint.

This keeps the exercise aligned with the current repository vocabulary, where the user contract uses `accountLifecycle` rather than a separate `status` field.

**Steps**:
1. Add to `openapi/components/schemas/user.yaml`:
   ```yaml
   UserAccountLifecycleUpdateRequest:
     type: object
     required:
       - accountLifecycle
     properties:
       accountLifecycle:
         type: string
         enum: [created, provisioned, active, paused, deleted]
   ```

2. Add to `openapi/paths/users.yaml`:
   ```yaml
   /api/users/{userId}/account-lifecycle:
     patch:
       tags:
         - Users
       summary: Update user account lifecycle
       operationId: updateUserAccountLifecycle
       parameters:
         - name: userId
           in: path
           required: true
           schema:
             type: integer
             format: int64
       requestBody:
         required: true
         content:
           application/json:
             schema:
               $ref: '../components/schemas/user.yaml#/UserAccountLifecycleUpdateRequest'
       responses:
         '200':
           description: Account lifecycle updated
         '403':
           description: Not authorized
         '404':
           description: User not found
   ```

3. Register the new path in `openapi/openapi.yaml`.

4. Run:
   ```bash
   ./gradlew compileOpenApi generateOpenApiModels
   ```

5. Implement the generated interface

## Key Takeaways

1. **Define API before coding** - Contract first, implementation second
2. **OpenAPI YAML** is the source of truth
3. **Generate, don't write** - Generated code saves time and reduces errors
4. **Frontend agrees first** - Frontend and backend can work in parallel
5. **Changes require updates** - Changing YAML regenerates code

---

## What's Next?

Now you understand how to design APIs. Let's learn how to **read existing code** to understand what others have written.

**[Next: Reading Code](06-reading-code.md)**