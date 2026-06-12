# Chapter 2: Layered Architecture

## The Solution: Separation of Concerns

In the previous chapter, we saw how dumping everything into one place creates problems. Now let's look at the solution: **Layered Architecture**.

The idea is simple: **separate your code into layers, where each layer has a specific job**.

## The Four Layers

Here's how we organize code in this template:

```
┌─────────────────────────────────────────────────────────────┐
│                      RESOURCE LAYER                         │
│                                                             │
│   "The Receptionist / Traffic Cop"                          │
│   - Handles HTTP requests/responses                         │
│   - Parses input, formats output                            │
│   - Delegates to UseCase or Service layer                   │
│   - NOTHING ELSE                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       USECASE LAYER                         │
│                                                             │
│   "The Concierge / Coordinator"                             │
│   - Contains BUSINESS LOGIC FLOW                             │
│   - Handles AUTHORIZATION (who can do what)                 │
│   - Orchestrates multiple services                          │
│   - Transaction boundaries                                  │
│   - May call Service OR Mapper directly                    │
│     (Use Mapper for simple CRUD, Service for complex logic) │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                       SERVICE LAYER                         │
│                                                             │
│   "The Specialist / Technician"                             │
│   - Contains TECHNICAL implementation                       │
│   - Knows HOW to do things                                  │
│   - Single responsibility per service                       │
│   - May be reused by multiple UseCases                      │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    REPOSITORY LAYER                         │
│                                                             │
│   "The File Clerk / Data Access"                            │
│   - Database operations only (CRUD)                         │
│   - SQL queries                                             │
│   - NO business logic                                       │
└─────────────────────────────────────────────────────────────┘
```

## Real-World Analogy: Hotel

Think of a hotel:

| Layer | Hotel Role | Responsibility |
|-------|------------|----------------|
| **Resource** | Receptionist | Greets guests, takes requests |
| **UseCase** | Concierge | Understands what you need, checks permissions, coordinates |
| **Service** | Room Service Chef | Actually prepares your food |
| **Repository** | Storage Clerk | Gets ingredients from the pantry |

When you order room service:
1. **Receptionist** (Resource) takes your call
2. **Concierge** (UseCase) says "yes, you're a guest, here's your order"
3. **Chef** (Service) prepares the food
4. **Storage** (Repository) provides the ingredients

The receptionist doesn't cook. The chef doesn't check if you're allowed to order. Each has ONE job.

## Code Example: Creating a User

Let's see how this works with code:

### Resource Layer

```java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {

    @Inject UserService userService;
    @Inject UsermetaUseCase usermetaUseCase;
    @Inject AuthenticatedUser authenticatedUser;

    @Override
    @Authenticated
    @GET
    @Path("/users/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getUserById(@PathParam("userId") Long userId) {
        // Just delegation. NO business logic here.
        return userService
            .findById(userId)
            .map(user -> Response.ok(toUserPublicResponse(user)).build())
            .orElse(
                Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("User not found"))
                    .build());
    }
}
```

**Notice**: The Resource layer:
- Is thin (just methods that delegate)
- Handles HTTP (status codes, error responses)
- Has `@Path`, `@GET`, `@POST` annotations
- Injects and uses UseCase/Service layers

### UseCase Layer

```java
@ApplicationScoped
public class UsermetaUseCase {

    public UserMeta getUserMeta(Long userId, Long requestingUserId) {
        // AUTHORIZATION: This is a UseCase concern
        if (!userId.equals(requestingUserId)) {
            throw new SecurityException("You can only access your own metadata");
        }

        // Get data (delegates to service)
        User user = userMapper.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        return parseMetaData(user.getUsermeta());
    }
}
```

**Notice**: The UseCase layer:
- Handles authorization ("who can do this")
- Orchestrates the flow
- Contains business rules
- Does NOT contain SQL or HTTP handling

### Service Layer

```java
@ApplicationScoped
public class UserService {

    @Inject UserMapper userMapper;

    @Transactional
    public User createAnonymousUser() {
        // Technical steps of creating a user
        User user = new User();
        user.setAccountLifecycle(AccountLifecycle.CREATED);
        user.setUsermeta(null);
        user.setSysmeta(null);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        
        // This is WHERE the actual INSERT happens
        userMapper.insert(user);
        
        return user;
    }

    public Optional<User> findById(Long id) {
        // Simple delegation to repository
        return userMapper.findById(id);
    }
}
```

**Notice**: The Service layer:
- Contains "how to" logic
- Coordinates technical steps
- Uses Repository layer for data access
- Does NOT make authorization decisions

### Repository Layer

```java
@Mapper
public interface UserMapper {
    Optional<User> findById(Long id);
    void insert(User user);
    void update(User user);
}
```

**Notice**: The Repository layer:
- Is a MyBatis mapper interface with Java annotations
- Contains NO business logic
- Just defines data access methods with @Select, @Insert, etc.

## Exercise 2.1: Layer Identification

Look at this code and identify which layer each section belongs to:

```java
public OrderResult createOrder(Long userId, Long productId, Integer quantity) {
    // 1. Check if user exists
    User user = userRepository.findById(userId);
    if (user == null) {
        throw new IllegalArgumentException("User not found");
    }
    
    // 2. Check if product exists
    Product product = productRepository.findById(productId);
    if (product == null) {
        throw new IllegalArgumentException("Product not found");
    }
    
    // 3. Check authorization (can user make orders?)
    if (!user.isActive()) {
        throw new SecurityException("User account is not active");
    }
    
    // 4. Calculate price
    BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(quantity));
    
    // 5. Create order record
    Order order = new Order();
    order.setUserId(userId);
    order.setProductId(productId);
    order.setQuantity(quantity);
    order.setTotalPrice(totalPrice);
    
    // 6. Save to database
    orderRepository.insert(order);
    
    // 7. Return result
    return new OrderResult(order);
}
```

**Questions**:
- Which lines are Repository layer?
- Which lines are Service layer?
- Which lines are UseCase layer?
- If this were properly separated, what would each layer do?

---

## The Dependency Rule

Here's an important principle: **Dependencies should flow downward, not upward**.

```
Resource → UseCase → Service → Repository
Resource → Service → Repository
```

This means:
- Resource can call UseCase for business flows
- Resource can call Service directly for simple operations
- UseCase can call Service
- Service can call Repository
- But Service should NOT call Resource
- And Repository should NOT call UseCase

---

## Discussion Questions

1. Why do you think we need the dependency rule?
2. What would happen if Service could call Resource?
3. Why do you think Repository is at the bottom of the hierarchy?

---

## Key Takeaways

1. **Each layer has a single, clear responsibility**
2. **Resource = HTTP handling (thin)**
3. **UseCase = Business flow + Authorization**
4. **Service = Technical implementation steps**
5. **Repository = Data access (SQL only)**
6. **Dependencies flow downward, never upward**
7. **Layers are not about more files - they're about appropriate distribution**

---

## What's Next?

Now that you understand the four layers, let's tackle the most confusing distinction: **UseCase vs Service**. Many developers struggle with knowing which code goes where.

**[Next: UseCase vs Service](03-usecase-vs-service.md)**