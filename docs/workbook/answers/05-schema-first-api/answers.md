# Chapter 5: Exercise Answers

## Exercise 5.1: Add a New Endpoint

You should have completed these steps:

### Step 1: Schema Added

In `openapi/components/schemas/user.yaml`, you added:

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

### Step 2: Path Added

In `openapi/paths/users.yaml`, you added:

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

And because this repository uses a modular root spec, you also registered the path in `openapi/openapi.yaml`.

### Step 3: Generate Code

You ran:
```bash
./gradlew compileOpenApi generateOpenApiModels
```

`compileOpenApi` reads the source spec at `openapi/openapi.yaml` and writes the compiled spec to `build/openapi-compiled/openapi.yaml`. `generateOpenApiModels` then generates code from that compiled spec.

### Step 4: Implementation

The generated interface method looks like:

```java
public interface UsersApi {
    @PATCH
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Response updateUserAccountLifecycle(
        @PathParam("userId") Long userId,
        UserAccountLifecycleUpdateRequest request
    );
}
```

You would implement it in `UsersApiImpl.java`:

```java
@Override
@Authenticated
@PATCH
@Path("/users/{userId}/account-lifecycle")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public Response updateUserAccountLifecycle(
        @PathParam("userId") Long userId,
        UserAccountLifecycleUpdateRequest request) {

    try {
        userUseCase.updateAccountLifecycle(userId, request.getAccountLifecycle());
        return Response.ok().build();
    } catch (SecurityException e) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity(new ErrorResponse(e.getMessage()))
            .build();
    } catch (IllegalArgumentException e) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(new ErrorResponse(e.getMessage()))
            .build();
    }
}
```

Notice that the Resource still stays thin. It delegates the authorization and business rule decisions to the UseCase instead of hard-coding project-specific role checks in the endpoint.

---

## Common Mistakes

### Mistake 1: Forgetting to Run Gradle Tasks

After modifying the source YAML under `openapi/`, you MUST run:
```bash
./gradlew compileOpenApi generateOpenApiModels
```

`compileOpenApi` refreshes `build/openapi-compiled/openapi.yaml`, and `generateOpenApiModels` uses that compiled spec to regenerate the classes. If you forget, you'll get compilation errors because the generated classes don't exist.

### Mistake 2: Wrong Ref Path

Make sure your `$ref` paths are correct:
```yaml
# Correct
$ref: '../components/schemas/user.yaml#/UserAccountLifecycleUpdateRequest'

# Wrong (missing ../)
$ref: 'components/schemas/user.yaml#/UserAccountLifecycleUpdateRequest'
```

### Mistake 3: Putting Implementation in Resource

Remember: Resource should be THIN. Don't put business logic there:

```java
// WRONG - business logic in resource
@Patch
public Response updateUserAccountLifecycle(Long userId, Request req) {
    User user = userMapper.findById(userId);
    if (user == null) throw new IllegalArgumentException();
    user.setAccountLifecycle(req.getAccountLifecycle());
    userMapper.update(user);
    return ok();
}

// RIGHT - delegation
@Patch
public Response updateUserAccountLifecycle(Long userId, Request req) {
    return userUseCase.updateAccountLifecycle(userId, req.getAccountLifecycle());
}
```

---

## Hints

Still stuck?

1. **Hint 1**: Start with YAML only. Don't write any Java until you've generated the code.

2. **Hint 2**: If you get "cannot find symbol" errors, you probably forgot to run the Gradle tasks.

3. **Hint 3**: Look at existing implementations in `UsersApiImpl.java` for patterns to follow.