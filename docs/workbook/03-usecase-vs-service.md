# Chapter 3: UseCase vs Service - The Crucial Distinction

## The Most Confusing Part

This is where most developers get confused. "Both sound like business logic!" But they're actually quite different.

## The Simple Answer

| | **UseCase** | **Service** |
|---|---|---|
| **Japanese** | ユースケース (what) | サービス (how) |
| **English** | Use Case | Service |
| **Answer to** | "What are we doing?" | "How do we do it?" |
| **Contains** | Flow + Authorization | Technical steps |
| **Example** | "Validate user, then create, then send email" | "Insert into DB", "Hash password" |

## Real-World Analogy: Restaurant Order

When you order food at a restaurant:

**UseCase (what happens)**:
1. Waiter takes your order
2. Waiter checks if you're allowed to order (not banned)
3. Waiter sends order to kitchen
4. Kitchen prepares food
5. Waiter brings food to you
6. Waiter records the transaction

**Service (how it happens)**:
- The chef knows HOW to cook each dish
- The grill knows HOW to sear meat at 500°F
- The refrigerator knows HOW to keep food cold

The **UseCase** is about the flow and authorization. The **Service** is about the technical knowledge.

## Code Example: User Registration

Let's see the difference in a real scenario: **User Registration**.

### The "Wrong" Way (No Separation)

```java
@Service
public class BadUserService {
    
    public User registerUser(String email, String password) {
        // UseCase logic mixed with Service logic
        
        // Validation (should be in UseCase)
        if (email == null) throw new IllegalArgumentException("Email required");
        if (!email.contains("@")) throw new IllegalArgumentException("Invalid email");
        
        // Authorization (should be in UseCase)
        // "Can this person register?" - THIS IS USECASE
        
        // Technical steps (correctly in Service)
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(hashPassword(password)); // HOW
        user.setCreatedAt(LocalDateTime.now());
        
        // Database (correctly in Service)
        userMapper.insert(user); // HOW
        
        // External calls (should be in UseCase)
        sendWelcomeEmail(email); // "We need to send email AFTER user is created" - THIS IS FLOW
        
        return user;
    }
}
```

This service is doing too much. It contains authorization decisions, flow control, AND technical implementation.

### The Right Way: Real Pattern from this Template

Let's look at how it's actually done in this codebase:

#### Resource Layer: Just Delegation

Looking at `UsersApiImpl.java`:

```java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {

    @Inject UserService userService;

    @Override
    @Authenticated
    @GET
    @Path("/users/{userId}")
    public Response getUserById(@PathParam("userId") Long userId) {
        // Just delegation - NO business logic
        return userService.findById(userId)
            .map(user -> Response.ok(toUserPublicResponse(user)).build())
            .orElse(Response.status(NOT_FOUND).build());
    }
}
```

#### UseCase Layer: Flow + Authorization

Looking at `FriendshipUseCase.java`:

```java
@ApplicationScoped
public class FriendshipUseCase {

    @Inject FriendshipService friendshipService;  // Complex business logic
    @Inject UserService userService;              // Entity operations
    @Inject FriendshipMapper friendshipMapper;    // Simple data access
    @Inject ProfileUseCase profileUseCase;
    
    /**
     * Creates a mutual friendship - THIS IS THE FLOW
     * What: Create friendship between two users
     * Who: Any authenticated user can send a friend request
     */
    @Transactional
    public Friendship createOrUpdateFriendship(
            Long senderId, Long recipientId, Map<String, Object> meta) {
        
        // Step 1: Validate recipient exists (part of the flow)
        userService.findById(recipientId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        
        // Step 2: Create friendship (delegates to Service for complex logic)
        Friendship friendship = friendshipService.createFriendship(
            senderId, recipientId, meta);
        
        // Step 3: Post-action - map to DTO
        return toFriendshipDto(friendship);
    }
}
```

#### Service Layer: Technical Implementation

Looking at `FriendshipService.java`:

```java
@ApplicationScoped
public class FriendshipService {

    @Inject FriendshipMapper friendshipMapper;

    /**
     * HOW: Create a mutual friendship
     * This contains complex technical logic for bidirectional relationships
     */
    @Transactional
    public Friendship createFriendship(Long senderId, Long recipientId, Map<String, Object> meta) {
        LocalDateTime now = LocalDateTime.now();
        
        // Create friendship from sender to recipient
        Friendship friendship = new Friendship();
        friendship.setSenderId(senderId);
        friendship.setRecipientId(recipientId);
        friendship.setUsermeta(serializeMeta(meta));
        friendship.setCreatedAt(now);
        friendship.setUpdatedAt(now);
        friendshipMapper.insert(friendship);

        // Create reverse friendship (mutual friendship)
        Friendship reverseFriendship = new Friendship();
        reverseFriendship.setSenderId(recipientId);
        reverseFriendship.setRecipientId(senderId);
        reverseFriendship.setUsermeta(serializeMeta(meta));
        // ...
        friendshipMapper.insert(reverseFriendship);

        return friendship;
    }
}
```

#### Mapper Layer: Data Access Only

Looking at `FriendshipMapper.java`:

```java
@Mapper
public interface FriendshipMapper {
    Optional<Friendship> findByParticipants(Long userId1, Long userId2);
    void insert(Friendship friendship);
    void update(Friendship friendship);
}
```

### Key Insight: When to Use Service vs Mapper Directly

Looking at the real code, you can see that:

| Situation | Call |
|-----------|------|
| Complex business logic (bidirectional create) | Use `FriendshipService` |
| Simple CRUD operations | Use `FriendshipMapper` directly from UseCase |
| Reusable technical operations | Create a Service method |

The UseCase decides **WHAT** happens and **WHO** can do it. The Service provides **HOW** for complex operations. Simple data access can go directly to Mapper.

## Another Example: Event Registration

Here's a more complex example: **Registering for an Event**.

### UseCase (What + Who)

```java
@ApplicationScoped
public class EventRegistrationUseCase {

    @Inject EventService eventService;
    @Inject UserService userService;
    @Inject EventMapper eventMapper;
    @Inject EventAttendeeMapper attendeeMapper;

    /**
     * What: Register a user for an event
     * Who: Only if:
     *   - User is not already registered
     *   - User is not banned from this event
     *   - Event has available spots
     */
    public Attendee registerForEvent(Long userId, Long eventId) {
        // Step 1: Get entities (UseCase can call Mapper directly)
        Event event = eventMapper.findById(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        
        // Step 2: Authorization checks (USECASE RESPONSIBILITY)
        if (isUserBanned(userId, eventId)) {
            throw new SecurityException("You are banned from this event");
        }
        
        if (attendeeMapper.findByUserAndEvent(userId, eventId).isPresent()) {
            throw new IllegalArgumentException("Already registered for this event");
        }
        
        long currentCount = attendeeMapper.countByEvent(eventId);
        if (currentCount >= event.getMaxAttendees()) {
            throw new IllegalStateException("Event is full");
        }
        
        // Step 3: Do the work (delegates to Service for complex operations)
        Attendee attendee = eventService.addAttendee(userId, eventId);
        
        // Step 4: Post-registration actions (FLOW)
        eventService.sendConfirmationEmail(userId, eventId);
        
        return attendee;
    }
    
    // Authorization helpers (USECASE RESPONSIBILITY)
    private boolean isUserBanned(Long userId, Long eventId) {
        // Check if user is banned
        return false; // Simplified
    }
}
```

### Service (How)

```java
@ApplicationScoped
public class EventService {

    @Inject EventMapper eventMapper;
    @Inject EventAttendeeMapper attendeeMapper;
    @Inject EmailService emailService;

    /**
     * HOW: Find an event by ID
     */
    public Optional<Event> findById(Long eventId) {
        return eventMapper.findById(eventId);
    }

    /**
     * HOW: Add an attendee to an event
     * This is a complex operation that belongs in Service
     */
    @Transactional
    public Attendee addAttendee(Long userId, Long eventId) {
        Attendee attendee = new Attendee();
        attendee.setUserId(userId);
        attendee.setEventId(eventId);
        attendee.setRegisteredAt(LocalDateTime.now());
        
        attendeeMapper.insert(attendee);
        
        // Update event attendee count
        eventMapper.incrementAttendeeCount(eventId);
        
        return attendee;
    }
    
    /**
     * HOW: Send confirmation email
     * This is a technical operation (could be reused elsewhere)
     */
    public void sendConfirmationEmail(Long userId, Long eventId) {
        User user = // get user
        Event event = // get event
        emailService.sendEmail(user.getEmail(), "Confirmation",
            "You are registered for " + event.getTitle());
    }
}
```

## The Key Insight

> **If you're asking "can this user do X?" → UseCase**
> **If you're asking "how do I do X technically?" → Service**

## Exercise 3.1: Separate the Concerns

Here's a single method. Separate it into UseCase and Service:

```java
public Order createOrder(Long userId, Long productId, Integer quantity) {
    // Check user exists
    User user = userRepository.findById(userId);
    if (user == null) throw new IllegalArgumentException("User not found");
    
    // Check product exists
    Product product = productRepository.findById(productId);
    if (product == null) throw new IllegalArgumentException("Product not found");
    
    // Check user is active
    if (!user.isActive()) throw new SecurityException("User not active");
    
    // Check stock
    if (product.getStock() < quantity) {
        throw new IllegalStateException("Insufficient stock");
    }
    
    // Calculate total
    BigDecimal total = product.getPrice().multiply(BigDecimal.valueOf(quantity));
    
    // Create order
    Order order = new Order();
    order.setUserId(userId);
    order.setProductId(productId);
    order.setQuantity(quantity);
    order.setTotalPrice(total);
    
    // Save
    orderRepository.insert(order);
    
    // Update stock
    product.setStock(product.getStock() - quantity);
    productRepository.update(product);
    
    // Send notification
    notificationService.sendOrderCreatedEmail(user.getEmail(), order);
    
    return order;
}
```

**Your task**:
1. Which authorization checks belong in UseCase?
2. Which technical operations belong in Service?
3. What would the Service methods look like?
4. What would the UseCase method look like?

---

## Key Takeaways

1. **UseCase = What + Who** (flow and authorization)
2. **Service = How** (technical implementation that may be reused)
3. **UseCase can call Service OR Mapper directly**
   - Use Mapper for simple CRUD operations
   - Use Service for complex technical operations that may be reused
4. **Authorization decisions are ALWAYS in UseCase**
5. **If you ask "can they?" → UseCase. If you ask "how?" → Service.**
6. **Service methods should be reusable by multiple UseCases**

---

## What's Next?

Now you understand layers and the UseCase/Service distinction. Let's look at **where each type of code should live** - the file organization.

**[Next: File Organization](04-file-organization.md)**