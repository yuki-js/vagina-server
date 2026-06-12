# Chapter 4: File Organization - Where Does This Go?

## The Question Every Developer Asks

When you're writing code, you constantly ask: **"Where should I put this?"**

If you don't have clear answers, you'll end up with:
- Everything in one file
- Random placement
- Inconsistent structure

This chapter gives you **clear rules** for where each type of code belongs.

## The Directory Structure

Here's the structure we use:

```
src/main/java/app/aoki/quarkuscrud/
├── entity/              # Domain models (the data)
├── mapper/              # MyBatis data access interfaces
├── repository/          # (not used in this template - mappers are used instead)
├── resource/            # REST API endpoints
├── service/             # Business logic (technical "how")
├── usecase/             # Business logic (flow + authorization)
└── support/             # Utilities, helpers, security context, exception mappers
```

## The Rules

### Rule 1: Entity = Data, No Logic

**Location**: `entity/`

**Contains**: Plain Java objects with fields, getters, setters. **No business logic**.

```java
// GOOD: entity/User.java
public class User {
    private Long id;
    private String email;
    private String passwordHash;
    private LocalDateTime createdAt;
    
    // Just getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    // ...
}
```

```java
// BAD: entity/User.java
public class User {
    private Long id;
    
    // THIS DOESN'T BELONG HERE
    public boolean canOrder() {
        return this.status == ACTIVE && hasValidSubscription();
    }
}
```

### Rule 2: Resource = HTTP, Just Delegation

**Location**: `resource/`

**Contains**: Thin REST endpoints that parse HTTP and delegate to a UseCase or Service as appropriate. **No business logic**.

```java
// GOOD: resource/UsersApiImpl.java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {
    
    @Inject UserService userService;
    @Inject RegistrationUseCase registrationUseCase;
    
    @POST
    @Path("/users")
    public Response createUser(CreateUserRequest request) {
        // Just delegation
        User user = registrationUseCase.registerUser(
            request.getEmail(), 
            request.getPassword()
        );
        return Response.ok(toResponse(user)).build();
    }
}
```

```java
// BAD: resource/UsersApiImpl.java
@ApplicationScoped
public class UsersApiImpl {
    
    @POST
    @Path("/users")
    public Response createUser(CreateUserRequest request) {
        // THIS DOESN'T BELONG HERE
        // Business logic in resource layer
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new IllegalArgumentException("Email exists");
        }
        
        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(hash(request.getPassword()));
        userRepository.save(user);
        
        emailService.sendWelcome(request.getEmail());
        
        return Response.ok(user);
    }
}
```

### Rule 3: UseCase = Flow + Authorization

**Location**: `usecase/`

**Contains**: Business flow and authorization rules. **No SQL (use Mapper), no HTTP**.

```java
// GOOD: usecase/RegistrationUseCase.java
@ApplicationScoped
public class RegistrationUseCase {
    
    @Inject UserMapper userMapper;          // Direct Mapper access is acceptable
    @Inject EmailService emailService;      // External service
    
    public User registerUser(String email, String password) {
        // Authorization check (USECASE)
        if (userMapper.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }
        
        // Create user entity directly
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(hashPassword(password));
        userMapper.insert(user);
        
        // Post-action (FLOW)
        emailService.sendWelcome(email);
        
        return user;
    }
}
```

### Rule 4: Service = Technical Implementation

**Location**: `service/`

**Contains**: Technical "how" operations. Reusable across UseCases. **No authorization decisions**.

```java
// GOOD: service/UserService.java
@ApplicationScoped
public class UserService {
    
    @Inject UserMapper userMapper;
    
    public User createUser(String email, String password) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(hashPassword(password));
        user.setCreatedAt(LocalDateTime.now());
        
        userMapper.insert(user);
        return user;
    }
    
    private String hashPassword(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }
    
    public Optional<User> findById(Long id) {
        return userMapper.findById(id);
    }
}
```

### Rule 5: Mapper = SQL Only

**Location**: `mapper/`

**Contains**: Database operations only using Java annotations. **No business logic**.

```java
// GOOD: mapper/UserMapper.java
@Mapper
public interface UserMapper {
    
    @Select("SELECT * FROM users WHERE id = #{id}")
    Optional<User> findById(Long id);
    
    @Select("SELECT * FROM users WHERE email = #{email}")
    Optional<User> findByEmail(String email);
    
    @Insert("INSERT INTO users (email, password_hash, created_at) VALUES (#{email}, #{passwordHash}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(User user);
    
    @Update("UPDATE users SET email = #{email}, password_hash = #{passwordHash}, updated_at = NOW() WHERE id = #{id}")
    void update(User user);
    
    @Delete("DELETE FROM users WHERE id = #{id}")
    void delete(Long id);
}
```

### Rule 6: Error Handling = Match the Project Structure

**Location**: There is no dedicated `exception/` package in this project.

**Contains**: Error handling is split by responsibility: exception mappers live in `support/`, and some feature-specific exceptions are defined close to the service or use case that uses them.

```java
// GOOD: support/WebApplicationExceptionMapper.java
@Provider
public class WebApplicationExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Override
    public Response toResponse(WebApplicationException exception) {
        return exception.getResponse();
    }
}
```

```java
// ALSO USED: usecase/LlmUseCase.java
public static class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
```

### Rule 7: Support = Cross-Cutting Concerns

**Location**: `support/`

**Contains**: Authentication context, utility methods, shared helpers, exception mappers.

```java
// GOOD: support/AuthenticatedUser.java
@ApplicationScoped
public class AuthenticatedUser {
    private final ThreadLocal<User> currentUser = new ThreadLocal<>();
    
    public void set(User user) {
        currentUser.set(user);
    }
    
    public User get() {
        return currentUser.get();
    }
}
```

## Quick Reference Table

| Code Type | Question It Answers | Location | Contains |
|-----------|-------------------|----------|----------|
| Entity | "What data?" | `entity/` | Fields, getters, setters |
| Resource | "HTTP endpoint?" | `resource/` | REST handlers, delegation |
| UseCase | "What flow? Who can?" | `usecase/` | Flow, authorization, may call Mapper or Service |
| Service | "How to do?" | `service/` | Technical operations (reusable) |
| Mapper | "SQL query?" | `mapper/` | Database operations |
| Error handling | "What error happened?" | `support/` or near the owning `service/` / `usecase/` | Exception mappers and project-local error types |
| Support | "Shared utility?" | `support/` | Cross-cutting concerns |

## Exercise 4.1: File Placement Quiz

For each of the following code snippets, identify which file they should be in:

**Snippet A**:
```java
public class User {
    private Long id;
    private String email;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
```

**Snippet B**:
```java
@Path("/api/users")
@POST
public Response createUser(CreateUserRequest req) {
    return registrationUseCase.register(req.getEmail(), req.getPassword());
}
```

**Snippet C**:
```java
if (!currentUser.getId().equals(userId)) {
    throw new SecurityException("Not authorized");
}
```

**Snippet D**:
```java
@Select("SELECT * FROM users WHERE id = #{id}")
Optional<User> findById(Long id);
```

**Snippet E**:
```java
public User createUser(String email, String password) {
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash(hash(password));
    userMapper.insert(user);
    return user;
}
```

**Snippet F**:
```java
throw new UserBannedException(userId, eventId);
```

## Discussion Questions

1. Why do you think we separate entity classes from API request/response models?
2. What would happen if UseCase put SQL in its code?
3. Can you think of a reason why Service should NOT make authorization decisions?

---

## Key Takeaways

1. **Each file type has ONE home**
2. **Entity = data, no logic**
3. **Resource = HTTP, just delegates**
4. **UseCase = flow + authorization**
5. **Service = technical "how"**
6. **Mapper = SQL only**
7. **When in doubt, ask "what question does this answer?"**

---

## What's Next?

Now that you know where things go, let's learn about **Schema-First API Design** - how to design APIs before writing code.

**[Next: Schema-First API Design](05-schema-first-api.md)**