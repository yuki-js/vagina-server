# Anti-Patterns & Red Flags Cheatsheet

## Red Flags by Layer

### 🚩 Resource Red Flags

| Red Flag | Problem | Fix |
|----------|---------|-----|
| SQL queries | Business logic in wrong layer | Move to Mapper |
| `if` statements for business rules | Business logic leak | Move to UseCase |
| `userMapper.findById()` calls | Direct data access | Use Service instead |
| 50+ lines of code | Doing too much | Split into layers |
| Database transactions | Resource shouldn't manage | Use `@Transactional` in UseCase |

**Bad Resource Example:**
```java
@POST
@Path("/users")
public Response createUser(CreateUserRequest req) {
    // ❌ SQL in resource
    if (userMapper.findByEmail(req.getEmail()).isPresent()) {
        throw new ConflictException();
    }
    
    // ❌ Business logic
    if (!req.getPassword().matches(".*[A-Z].*")) {
        throw new BadRequestException("Password needs uppercase");
    }
    
    // ❌ Creating entity with logic
    User user = new User();
    user.setPassword(hash(req.getPassword())); // ❌ HOW in resource
    userMapper.insert(user);
    
    return ok(user);
}
```

---

### 🚩 UseCase Red Flags

| Red Flag | Problem | Fix |
|----------|---------|-----|
| No authorization checks | Missing business rules | Add auth |
| HTTP handling | Wrong abstraction | Should be in Resource |
| Single method over 100 lines | Doing too much | Split |
| Calling Service for operations that should be in Mapper | Unnecessary abstraction | Call Mapper directly |

**Bad UseCase Example:**
```java
public class BadUseCase {
    @Inject UserService userService;
    
    public void createUser(String email) {
        // ❌ Unnecessary delegation - UserService just wraps UserMapper
        if (userService.emailExists(email)) {
            throw new IllegalArgumentException();
        }
        User user = userService.createUser(email);
    }
}
```

**Good UseCase Pattern (direct Mapper access):**
```java
public class GoodUseCase {
    @Inject UserMapper userMapper;
    
    public void createUser(String email) {
        // ✓ Direct Mapper access is acceptable for UseCase
        if (userMapper.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException();
        }
        User user = new User(email);
        userMapper.insert(user);
    }
}
```

---

### 🚩 Service Red Flags

| Red Flag | Problem | Fix |
|----------|---------|-----|
| `SecurityException` | Authorization in wrong layer | Move to UseCase |
| `if (!userId.equals(...))` | Authorization check | UseCase should do this |
| Business rule validation | Not Service responsibility | UseCase |
| Complex orchestration | Service should be single operation | Refactor |

**Bad Service Example:**
```java
@Service
public class BadOrderService {
    
    public Order createOrder(Long userId, Long productId) {
        User user = userRepository.findById(userId);
        
        // ❌ Authorization in Service!
        if (!user.isActive()) {
            throw new SecurityException("User not active"); // ❌ WRONG LAYER
        }
        
        // ❌ Business rule in Service
        if (user.getOrderCount() > 100) {
            throw new BusinessException("Too many orders"); // ❌ WRONG LAYER
        }
        
        // This part is OK
        Order order = new Order(userId, productId);
        orderRepository.save(order);
        
        return order;
    }
}
```

---

### 🚩 Repository/Mapper Red Flags

| Red Flag | Problem | Fix |
|----------|---------|-----|
| Business validation | Mapper shouldn't validate | Move to UseCase |
| `if` statements for business rules | Wrong layer | UseCase |
| Transaction management | Should be in UseCase | `@Transactional` in UseCase |
| Logging business actions | Not data access | Use UseCase/Service |

**Bad Mapper Example:**
```java
@Mapper
public interface BadUserMapper {
    
    void insert(User user) {
        // ❌ Business logic in Mapper!
        if (user.getEmail() == null) {
            throw new IllegalArgumentException("Email required"); // ❌ WRONG
        }
        if (countByEmail(user.getEmail()) > 0) {
            throw new DuplicateException("Email exists"); // ❌ WRONG
        }
        // SQL...
    }
}
```

---

### 🚩 Entity Red Flags

| Red Flag | Problem | Fix |
|----------|---------|-----|
| `canDoSomething()` methods | Business logic | Move to Service/UseCase |
| `calculate()` methods | Computation | Move to Service |
| `if` statements | Business rules | Move out |
| Static methods | Often business logic | Review |

**Bad Entity Example:**
```java
public class User {
    private Long id;
    private String email;
    private String passwordHash;
    private Integer orderCount;
    private SubscriptionStatus subscription;
    
    // ❌ Business logic in entity!
    public boolean canPlaceOrder() {
        return this.status == ACTIVE 
            && this.subscription.isValid()  // ❌ WRONG LAYER
            && this.orderCount < 1000;
    }
    
    // ❌ Computation in entity!
    public BigDecimal getDiscount() {
        if (this.orderCount > 100) return BigDecimal.TEN;  // ❌ WRONG
        if (this.orderCount > 50) return BigDecimal.FIVE;  // ❌ WRONG
        return BigDecimal.ZERO;
    }
}
```

---

## Code Smell Quick Reference

### Size Smells

| Smell | Threshold | Problem |
|-------|-----------|---------|
| **God Method** | > 50 lines | Does too much |
| **God Class** | > 500 lines | Too many responsibilities |
| **Long Parameter List** | > 4 params | Should group into DTO |
| **Deep Nesting** | > 3 levels | Hard to follow |

### Naming Smells

| Smell | Example | Problem |
|-------|---------|---------|
| **Non Descriptive** | `doIt()`, `handle()` | What does it do? |
| **Inconsistent** | `createUser` vs `makeUser` | Which is correct? |
| **Single Letter** | `x`, `y`, `z` (except loops) | Unclear purpose |
| **Hungarian Notation** | `strName`, `iCount` | Java not C |

### Structure Smells

| Smell | Example | Problem |
|-------|---------|---------|
| **Shotgun Surgery** | Change requires many files | Poor cohesion |
| **Parallel Inheritance** | Two class hierarchies | Duplication |
| **Swiss Army Knife** | God class with everything | Too many responsibilities |
| **Boolean Parameter** | `doSomething(boolean flag)` | What does flag mean? |

---

## Violation Quick Test

Ask these questions about any code:

| Question | If YES | Layer |
|----------|--------|-------|
| Is there SQL? | → | Mapper |
| Is there HTTP? | → | Resource |
| Is there "Can they?" | → | UseCase |
| Is there "How?" | → | Service |
| Is there just data? | → | Entity |
| Is there an error? | → | Exception |

---

## The Fix Template

When you find a violation:

```
1. IDENTIFY - What layer is this really?
2. EXTRACT - Move to the correct layer
3. DELEGATE - Update callers to use new location
4. TEST - Verify behavior unchanged
```

---

*Print this and use it during code reviews!*