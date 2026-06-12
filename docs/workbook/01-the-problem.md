# Chapter 1: The Problem with "Just Make It Work"

## The Starting Point: Your First Backend

You know the drill. A new feature is needed. You open your IDE, create a controller, and start writing:

```java
@RestController
@RequestMapping("/api/users")
public class UserController {
    
    @PostMapping
    public Response createUser(String name, String email) {
        // Check if name is empty
        if (name == null || name.isEmpty()) {
            return Response.status(400).entity("Name required").build();
        }
        
        // Check if email is valid
        if (email == null || !email.contains("@")) {
            return Response.status(400).entity("Valid email required").build();
        }
        
        // Check database connection
        try {
            Connection conn = DriverManager.getConnection(DB_URL);
            
            // Check if user exists
            PreparedStatement check = conn.prepareStatement(
                "SELECT * FROM users WHERE email = ?");
            check.setString(1, email);
            ResultSet rs = check.executeQuery();
            if (rs.next()) {
                return Response.status(409).entity("User exists").build();
            }
            
            // Create user
            PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO users (name, email, created_at) VALUES (?, ?, ?)");
            insert.setString(1, name);
            insert.setString(2, email);
            insert.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            insert.executeUpdate();
            
            // Log the creation
            Logger.getAnonymousLogger().info("User created: " + email);
            
            // Return success
            return Response.ok().entity("User created").build();
            
        } catch (SQLException e) {
            return Response.status(500).entity("DB error").build();
        }
    }
}
```

**Looks fine, right? It works.**

But then comes the requirements:
- Add password validation
- Add role assignment
- Add email verification
- Add rate limiting
- Add audit logging
- Add caching
- Add unit tests

Six months later, your controller is 2,000 lines. Good luck finding anything.

---

## Exercise 1.1: Bug Hunt

Here's a simplified version of a real production controller. Your task: **find the bugs**.

```java
@PostMapping("/orders")
public Response createOrder(Long userId, Long productId, Integer quantity) {
    // Get user
    User user = userRepository.findById(userId);
    
    // Get product
    Product product = productRepository.findById(productId);
    
    // Create order
    Order order = new Order();
    order.setUserId(userId);
    order.setProductId(productId);
    order.setQuantity(quantity);
    order.setTotalPrice(product.getPrice() * quantity);
    
    // Save
    orderRepository.save(order);
    
    // Send email
    emailService.sendOrderConfirmation(user.getEmail(), order);
    
    return Response.ok(order);
}
```

**Questions**:
1. What happens if `user` is null?
2. What happens if `product` is null?
3. What happens if `quantity` is negative or zero?
4. What happens if `product.getPrice()` returns null?
5. Should the email be sent before or after the transaction commits?

---

## The Core Problems

When we dump everything into one place, we create three fundamental problems:

### 1. Mixing Concerns

Our controller handles:
- **HTTP concerns**: Request parsing, response formatting, status codes
- **Business concerns**: Validation, authorization, business rules
- **Data concerns**: Database queries, transactions
- **External concerns**: Sending emails, logging, caching

These are **four different jobs** that should be handled by **four different pieces of code**.

### 2. Hidden Dependencies

In the "god controller" pattern, dependencies are implicit. You can't easily:
- Test the business logic without a running server
- Reuse business logic from another endpoint
- See all places that use a particular database table

### 3. Scattered Knowledge

When everything is in one place, there's no clear "owner" for any piece of logic:
- Who validates the email format? The controller? The service?
- Who checks if a user is authorized? The controller? The service?
- Who handles database transactions? The controller? The service?

---

## The Maintainability Test

Here's a simple test to see if your code is maintainable:

**Can you answer these questions without searching through thousands of lines?**

1. "Where is the user creation logic?" → Should point to ONE place
2. "Who is allowed to create a user?" → Should be in ONE place
3. "How do I add a new field to user?" → Should touch MINIMAL files
4. "How do I test user creation?" → Should be testable WITHOUT the HTTP layer

If you can't answer these quickly, your code has a maintainability problem.

---

## Discussion Questions

1. Think of a time when you had to modify a "god class." What made it hard?
2. Have you ever been afraid to change code because you didn't know what would break?
3. What would make you feel confident about making changes?

---

## Key Takeaways

1. **"It works" is not the same as "it's good"**
2. **Code is read more often than it's written** - optimize for reading
3. **Single responsibility = each piece has ONE job**
4. **Separation of concerns = different jobs go to different places**
5. **The goal is not fewer files - it's appropriate distribution of responsibility**

---

## What's Next?

In the next chapter, we'll look at the **Layered Architecture** pattern that solves these problems by giving each concern its own layer.

**[Next: Layered Architecture](02-layered-architecture.md)**