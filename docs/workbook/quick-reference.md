# Quick Reference Guide

## Layer Responsibilities

| Layer | Japanese | Question It Answers | Examples |
|-------|----------|-------------------|----------|
| **Resource** | リソース | "HTTP endpoint?" | `@GET`, `@POST`, parse request |
| **UseCase** | ユースケース | "What flow? Who can?" | Authorization, orchestration |
| **Service** | サービス | "How to do?" | Technical operations |
| **Repository** | リポジトリ | "Database query?" | CRUD, SQL |

## File Locations

```
src/main/java/app/aoki/quarkuscrud/
├── entity/           # Plain data objects
├── resource/         # REST endpoints (thin)
├── service/          # Technical "how"
├── usecase/          # Flow + authorization
├── mapper/           # MyBatis interfaces
└── support/          # Utilities, context, exception mappers
```

## The Golden Rules

1. **Resource = Thin** - Just delegation, no business logic
2. **Authorization in UseCase** - Never in Service or Resource
3. **Service = Reusable** - Same service can be used by multiple UseCases
4. **Repository = SQL** - No business logic, just data access
5. **One Responsibility** - Each class/method does one thing

## Code Snippets

### Resource (Thin)

```java
@Inject UserUseCase userUseCase;

@POST
@Path("/users")
public Response createUser(CreateUserRequest req) {
    return userUseCase.createUser(req.getEmail(), req.getPassword());
}
```

### UseCase (Flow + Auth)

```java
public User createUser(String email, String password) {
    // Validation
    validateEmail(email);
    
    // Authorization
    if (emailExists(email)) {
        throw new IllegalArgumentException("Email exists");
    }
    
    // Orchestrate
    User user = userService.createUser(email, password);
    emailService.sendWelcome(email);
    
    return user;
}
```

### Service (Technical)

```java
public User createUser(String email, String password) {
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash(hashPassword(password));
    userMapper.insert(user);
    return user;
}
```

### Repository (SQL)

```java
@Mapper
public interface UserMapper {
    Optional<User> findById(Long id);
    void insert(User user);
}
```

## Debugging Checklist

When something breaks:

1. **Resource layer OK?** → Check HTTP status, request parsing
2. **UseCase layer OK?** → Check authorization, business rules
3. **Service layer OK?** → Check technical implementation
4. **Repository layer OK?** → Check SQL, database connection

## Schema-First Workflow

```bash
# 1. Edit source OpenAPI YAML under openapi/
vim openapi/paths/users.yaml
vim openapi/components/schemas/user.yaml

# 2. Compile source spec to build/openapi-compiled/openapi.yaml
./gradlew compileOpenApi

# 3. Generate code from the compiled spec
./gradlew generateOpenApiModels

# 4. Implement interface
vim src/.../resource/UsersApiImpl.java

# 5. Run tests
./gradlew test
```

## Import When Confused

```
Is it HTTP handling? → Resource
Is it database? → Repository/Mapper
Is it authorization? → UseCase
Is it reusable technical logic? → Service
Is it just data? → Entity
Is it an error? → `support/` for exception mappers, otherwise keep the error type near the owning `usecase/` or `service/`