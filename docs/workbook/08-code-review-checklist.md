# Bonus Track: Code Review Checklist for Layered Architecture

## Introduction

Code reviews are your best opportunity to teach and enforce architectural patterns. This checklist helps reviewers systematically evaluate code that follows layered architecture.

## The Review Mindset

Before starting, remember:

- **The goal is to help, not punish** - You're teaching patterns, not catching mistakes
- **Praise good patterns** - Reinforce what's done right
- **Ask questions first** - "Can you explain why this is here?" often reveals better solutions
- **Distinguish rules from preferences** - Some things are opinions, some are patterns

---

## General Layer Checks

### Is Each Layer in Its Proper Place?

Ask: "Does this code belong to the layer where I found it?"

| If you see... | In... | Question to ask |
|---------------|-------|-----------------|
| SQL queries | Resource | "Why is database logic in the HTTP layer?" |
| Authorization checks | Service | "Is this business rule or security?" |
| HTTP handling | UseCase | "Should this be in Resource?" |
| Business rules | Repository | "Why is business logic in data access?" |

---

## Resource Layer Review

### Thin Controller Check

**✓ Good**: Resource only handles HTTP
```java
@POST
@Path("/users")
public Response createUser(CreateUserRequest req) {
    return userUseCase.createUser(req.getEmail(), req.getPassword());
}
```

**✗ Bad**: Resource contains business logic
```java
@POST
@Path("/users")
public Response createUser(CreateUserRequest req) {
    // ❌ Business logic in resource
    if (!email.contains("@")) throw new BadRequestException();
    if (userRepo.findByEmail(email).isPresent()) throw new ConflictException();
    User user = new User(email, hash(password));
    userRepo.save(user);
    emailService.sendWelcome(email);
    return ok(user);
}
```

### Resource Review Checklist

- [ ] Is the method doing only HTTP handling (parse, delegate, format)?
- [ ] Are exceptions converted to proper HTTP status codes?
- [ ] Is `@Authenticated` used for protected endpoints?
- [ ] Are `@Path` and HTTP method annotations correct?
- [ ] Does it inject and use a UseCase or Service appropriately for the endpoint?

---

## UseCase Layer Review

### Authorization in UseCase Check

**✓ Good**: Authorization is explicit
```java
public UserMeta updateUserMeta(Long userId, Long requestingUserId, UserMeta metaData) {
    // ✓ Authorization check
    if (!userId.equals(requestingUserId)) {
        throw new SecurityException("You can only update your own metadata");
    }
    // ... rest of logic
}
```

**✗ Bad**: No authorization or it's in the wrong place
```java
// ❌ No authorization at all
public UserMeta updateUserMeta(Long userId, UserMeta metaData) {
    userMapper.update(userId, metaData);
    return metaData;
}

// ❌ Authorization in Service (wrong layer)
public class UserService {
    public void updateMeta(Long userId, UserMeta metaData, Long requestingUserId) {
        if (!userId.equals(requestingUserId)) throw new SecurityException(); // ❌
        // ...
    }
}
```

### Flow Check

**✓ Good**: Clear orchestration
```java
public Order createOrder(Long userId, Long productId, Integer quantity) {
    // 1. Validate
    User user = getUserOrThrow(userId);
    
    // 2. Authorize
    if (!user.canOrder()) throw new SecurityException();
    
    // 3. Execute
    Order order = orderService.createOrder(user, product, quantity);
    
    // 4. Post-action
    notificationService.sendOrderConfirmation(user, order);
    
    return order;
}
```

### UseCase Review Checklist

- [ ] Is authorization handled in UseCase, not Service or Resource?
- [ ] Are exceptions thrown for business rule violations?
- [ ] Is the flow clear (validate → authorize → execute → post-action)?
- [ ] Does UseCase delegate technical work to Service or call Mapper directly?
- [ ] Is `@Transactional` used for operations that modify data?
- [ ] Are all side effects (emails, notifications) intentional and after the main operation?
- [ ] Is Service used only for complex/reusable operations, not simple CRUD?

---

## Service Layer Review

### No Authorization Check

**✓ Good**: Service focuses on technical "how"
```java
public class OrderService {
    public Order createOrder(User user, Product product, Integer quantity) {
        // ✓ Pure technical logic
        BigDecimal total = product.getPrice().multiply(quantity);
        Order order = new Order(user, product, quantity, total);
        orderRepository.save(order);
        return order;
    }
}
```

**✗ Bad**: Service making decisions
```java
public class OrderService {
    public Order createOrder(Long userId, Long productId) {
        User user = userRepository.findById(userId);
        // ❌ This is authorization, not service responsibility
        if (!user.isActive()) throw new SecurityException("User not active");
        // ...
    }
}
```

### Reusability Check

Ask: "Could another UseCase use this Service method?"

**✓ Good**: Reusable technical operations
```java
public class UserService {
    // ✓ Reusable: any UseCase can call this
    public void updatePassword(Long userId, String newPassword) {
        user.setPasswordHash(hash(newPassword));
        userMapper.update(user);
    }
}
```

**✗ Bad**: UseCase-specific logic in Service
```java
public class UserService {
    // ❌ Only for registration, shouldn't be generic
    public void registerNewUser(String email, String password) {
        if (userMapper.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email exists"); // ❌ Business rule
        }
        // ...
    }
}
```

### Service Review Checklist

- [ ] Does Service contain only technical "how" logic?
- [ ] Are no authorization decisions made in Service?
- [ ] Are methods reusable by multiple UseCases?
- [ ] Is Service used for complex operations, not simple CRUD (that could be Mapper-only)?
- [ ] Is `@Transactional` used appropriately?
- [ ] Are technical validations (null checks, range checks) in Service?

---

## Repository Layer Review

### SQL Only Check

**✓ Good**: Data access only
```java
@Mapper
public interface UserMapper {
    Optional<User> findById(Long id);
    void insert(User user);
    void update(User user);
}
```

**✗ Bad**: Contains business logic
```java
@Mapper
public interface UserMapper {
    void insert(User user) {
        // ❌ Business logic in mapper
        if (user.getEmail() == null) throw new IllegalArgumentException();
        if (countByEmail(user.getEmail()) > 0) throw new DuplicateException();
        // SQL...
    }
}
```

### Repository Review Checklist

- [ ] Are there only CRUD operations?
- [ ] Is there no business logic (validation, authorization)?
- [ ] Are complex queries documented with comments?
- [ ] Are proper SQL practices followed (parameterized queries)?

---

## Entity Review

### Anemia Check

**✓ Good**: Entity is just data
```java
public class User {
    private Long id;
    private String email;
    private String passwordHash;
    private LocalDateTime createdAt;
    
    // Getters and setters only
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    // ... other getters/setters
}
```

**✗ Bad**: Fat entity with business logic
```java
public class User {
    private Long id;
    private String email;
    
    // ❌ Business logic in entity
    public boolean canOrder() {
        return this.status == ACTIVE && subscription.isValid();
    }
    
    // ❌ Computed field
    public BigDecimal getDiscount() {
        return this.orders > 100 ? BigDecimal.TEN : BigDecimal.ZERO;
    }
}
```

### Entity Review Checklist

- [ ] Is the entity just data (fields + getters/setters)?
- [ ] Are there no business methods?
- [ ] Are computed properties calculated elsewhere (in Service or UseCase)?

---

## File Organization Review

### File Placement Check

Ask: "Would a new developer know where to find this?"

| Code Type | Expected Location |
|-----------|------------------|
| HTTP handlers | `resource/` |
| Business flow + auth | `usecase/` |
| Technical operations | `service/` |
| Database operations | `mapper/` |
| Data objects | `entity/` |
| Error handling types | `support/` for exception mappers, or near the owning `service/` / `usecase/` |

### File Organization Review Checklist

- [ ] Can you name each class's file by its layer?
- [ ] Are file names descriptive (e.g., `UserService`, not `Util`)?
- [ ] Are related classes grouped logically?

---

## Red Flags to Watch For

### 🚩 Code Smells

| Smell | Why It's a Problem |
|-------|-------------------|
| **God Method** | Method over 50 lines likely does too much |
| **Deep Nesting** | More than 2-3 levels of nesting indicates complex logic |
| **Magic Numbers** | No explanation for constants like `if (value > 86400)` |
| **Dead Code** | Commented-out or unreachable code |
| **Long Parameter List** | More than 3-4 parameters suggests grouping needed |
| **Shotgun Surgery** | One change requires many file modifications |

### 🚩 Pattern Violations

| Violation | Quick Test |
|-----------|-----------|
| Service has `if (userId.equals(...))` | Probably authorization |
| Resource has SQL | Should be in Mapper |
| Entity has `calculate()` | Should be in Service |
| UseCase calls Service that just wraps Mapper | Should call Mapper directly |
| Mapper has business validation | Should be in UseCase/Service |

---

## The Review Conversation

### Questions to Ask

1. **"What layer does this belong to?"** - Helps developer self-identify issues
2. **"Could this method be reused elsewhere?"** - Reveals if it's in the right layer
3. **"Who is responsible for this decision?"** - Clarifies authorization ownership
4. **"What happens if business rules change?"** - Tests separation quality
5. **"Is this testable without the database/HTTP?"** - Reveals coupling issues

### Phrases to Use

| Instead of... | Try... |
|--------------|--------|
| "This is wrong" | "What if we moved this to the Service layer?" |
| "Don't do this" | "Can you explain why this is in UseCase?" |
| "You should..." | "Have you considered...?" |
| "This breaks the pattern" | "In our architecture, Services handle..." |

---

## Summary: Quick Checklist

Before approving a PR, verify:

- [ ] Resource is thin (HTTP handling only)
- [ ] UseCase handles authorization + flow, calls Mapper or Service as appropriate
- [ ] Service has only technical logic (complex/reusable operations)
- [ ] Repository has only data access
- [ ] Entity is anemic (data only)
- [ ] Each file is in the correct directory
- [ ] No pattern violations detected
- [ ] Code is readable and maintainable

---

## Practice Exercise

Review this code and identify all issues:

```java
@Path("/api/orders")
@POST
public Response createOrder(Long userId, Long productId, Integer quantity) {
    User user = userRepository.findById(userId);
    if (user == null) return 404();
    
    Product product = productRepository.findById(productId);
    if (product.getStock() < quantity) return 400("Out of stock");
    
    if (!user.isPremium() && quantity > 10) {
        return 403("Max 10 for non-premium");
    }
    
    BigDecimal total = product.getPrice().multiply(quantity);
    
    Order order = new Order(userId, productId, quantity, total);
    orderRepository.save(order);
    
    emailService.sendConfirmation(user.getEmail(), order);
    
    return ok(order);
}
```

<details>
<summary>Click to see issues</summary>

1. **Resource**: Contains business logic (authorization check, stock check)
2. **Authorization**: Should be in UseCase (`if (!user.isPremium())`)
3. **Business rule**: "Max 10 for non-premium" is in wrong layer
4. **Validation**: Stock check should be in UseCase
5. **Flow**: Email sent before transaction commits (potential issue)
6. **No layer separation**: Everything in Resource

</details>