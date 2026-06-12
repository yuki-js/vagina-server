# Layer Architecture Cheatsheet

## The Four Layers

```
┌─────────────────────────────────────────────────────────────┐
│  RESOURCE (警官) - HTTP Handling                           │
│  "The Traffic Cop"                                        │
│  Ask: "Is this HTTP?"                                     │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  USECASE (受付) - Flow + Authorization                    │
│  "The Concierge"                                          │
│  Ask: "What + Who?"                                       │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  SERVICE (サービス) - Technical Implementation            │
│  "The Technician"                                         │
│  Ask: "How?"                                              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  REPOSITORY/MAPPER (リポジトリ) - Data Access            │
│  "The File Clerk"                                         │
│  Ask: "Database?"                                         │
└─────────────────────────────────────────────────────────────┘
```

---

## Quick Reference Table

| Layer | Japanese | Question | Contains | Location |
|-------|----------|----------|----------|----------|
| **Resource** | リソース | "HTTP?" | HTTP handling, delegation | `resource/` |
| **UseCase** | ユースケース | "What + Who?" | Flow, authorization | `usecase/` |
| **Service** | サービス | "How?" | Technical operations | `service/` |
| **Repository** | リポジトリ | "DB?" | Data access (CRUD) | `mapper/` |

---

## File Locations

```
src/main/java/app/aoki/quarkuscrud/
├── entity/              # Plain data objects (fields + getters/setters)
├── resource/            # REST endpoints (@Path, @GET, @POST)
├── service/             # Business logic (technical "how")
├── usecase/             # Business logic (flow + authorization)
├── mapper/              # MyBatis interfaces (SQL operations)
└── support/             # Utilities, auth context, exception mappers
```

---

## Code Patterns

### Resource (Thin - Just Delegation)

```java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {

    @Inject UserUseCase userUseCase;

    @POST
    @Path("/users")
    public Response createUser(CreateUserRequest req) {
        // Just delegation - NO business logic
        return userUseCase.createUser(req.getEmail(), req.getPassword());
    }
}
```

**Rule**: Resource does HTTP parsing and delegation ONLY.

---

### UseCase (Flow + Authorization)

```java
@ApplicationScoped
public class UserUseCase {

    @Inject UserService userService;
    @Inject EmailService emailService;

    public User createUser(String email, String password) {
        // 1. Validation
        validateEmail(email);
        
        // 2. Authorization check (USECASE RESPONSIBILITY)
        if (emailExists(email)) {
            throw new IllegalArgumentException("Email already exists");
        }
        
        // 3. Execute (delegates to Service)
        User user = userService.createUser(email, password);
        
        // 4. Post-action (flow)
        emailService.sendWelcome(email);
        
        return user;
    }
}
```

**Rule**: UseCase handles WHAT happens and WHO can do it.

---

### Service (Technical "How")

```java
@ApplicationScoped
public class UserService {

    @Inject UserMapper userMapper;

    public User createUser(String email, String password) {
        // Technical implementation only
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
}
```

**Rule**: Service handles HOW to do things. No authorization.

---

### Repository/Mapper (Data Access)

```java
@Mapper
public interface UserMapper {
    Optional<User> findById(Long id);
    Optional<User> findByEmail(String email);
    void insert(User user);
    void update(User user);
    void delete(Long id);
}
```

**Rule**: Mapper handles database operations ONLY. No business logic.

---

## Decision Tree

```
Is it an HTTP endpoint? (has @Path, @GET, @POST)
    └── YES → RESOURCE

Does it answer "Can they?" or "What happens first?"
    └── YES → USECASE

Does it answer "How do we do X?" (technical steps)
    └── YES → SERVICE

Does it answer "Database operation?" (CRUD)
    └── YES → REPOSITORY/MAPPER

Is it just data? (fields + getters/setters)
    └── YES → ENTITY

Is it an error type?
    └── YES → EXCEPTION
```

---

## Authorization Always in UseCase

```
✗ WRONG: Authorization in Service
public class OrderService {
    public void createOrder(Long userId, ...) {
        if (!user.isActive()) throw new SecurityException(); // ❌ HERE
        // ...
    }
}

✓ RIGHT: Authorization in UseCase
public class OrderUseCase {
    public void createOrder(Long userId, ...) {
        if (!user.isActive()) throw new SecurityException(); // ✓ HERE
        orderService.createOrder(userId, ...);
    }
}
```

---

## Key Rules

1. **Resource = Thin** - Just parse HTTP, delegate
2. **UseCase = Auth + Flow** - What, who, when
3. **Service = Technical** - How, reusable
4. **Repository = SQL** - Data access only
5. **Entity = Data** - No business logic
6. **Exception = Errors** - Business error types

---

*Print this and keep it at your desk!*