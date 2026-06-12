# Chapter 4: Exercise Answers

## Exercise 4.1: File Placement Quiz

### Snippet A

```java
public class User {
    private Long id;
    private String email;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}
```

**Answer**: `entity/User.java`

**Reason**: This is a plain data object with fields and getters/setters. No business logic.

---

### Snippet B

```java
@Path("/api/users")
@POST
public Response createUser(CreateUserRequest req) {
    return registrationUseCase.register(req.getEmail(), req.getPassword());
}
```

**Answer**: `resource/UsersApiImpl.java`

**Reason**: This is an HTTP endpoint (has @Path, @POST annotations). It just parses the request and delegates to a UseCase.

---

### Snippet C

```java
if (!currentUser.getId().equals(userId)) {
    throw new SecurityException("Not authorized");
}
```

**Answer**: `usecase/*.java` (UseCase layer)

**Reason**: This is an authorization check ("who can do this?"). Authorization belongs in UseCase.

---

### Snippet D

```java
@Select("SELECT * FROM users WHERE id = #{id}")
Optional<User> findById(Long id);
```

**Answer**: `mapper/UserMapper.java`

**Reason**: This is a database query (has @Select annotation from MyBatis). Data access belongs in Mapper.

---

### Snippet E

```java
public User createUser(String email, String password) {
    User user = new User();
    user.setEmail(email);
    user.setPasswordHash(hash(password));
    userMapper.insert(user);
    return user;
}
```

**Answer**: `service/UserService.java`

**Reason**: This is the "how" of creating a user - hashing password, inserting into database. Technical implementation belongs in Service.

---

### Snippet F

```java
throw new UserBannedException(userId, eventId);
```

**Answer**: Near the feature that owns it, for example `usecase/...` or `service/...`

**Reason**: This is a custom exception representing a specific error type, but this project does not have a dedicated `exception/` package. Error types are kept near the code that owns them, while shared exception mappers live in `support/`.

---

## Quick Reference

| Snippet | Layer | File Type |
|---------|-------|-----------|
| A | Domain | `entity/` |
| B | Resource | `resource/` |
| C | UseCase | `usecase/` |
| D | Repository | `mapper/` |
| E | Service | `service/` |
| F | Error | Near the owning `usecase/` or `service/` |

---

## Hints

Still stuck? Use this decision tree:

```
Is it an HTTP endpoint? (has @Path, @GET, etc.)
    → RESOURCE

Is it a database query? (has @Select, @Insert, etc.)
    → MAPPER

Is it "can they do this?" / "what happens first?"
    → USECASE

Is it "how do we do X?" / technical steps?
    → SERVICE

Is it just data with no logic?
    → ENTITY

Is it an error type?
    → Keep it near the owning USECASE/SERVICE (or in `support/` for exception mappers)