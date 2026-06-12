# Chapter 1: Exercise Answers

## Exercise 1.1: Bug Hunt

### The Problematic Code

```java
@PostMapping("/orders")
public Response createOrder(Long userId, Long productId, Integer quantity) {
    User user = userRepository.findById(userId);
    Product product = productRepository.findById(productId);
    
    Order order = new Order();
    order.setUserId(userId);
    order.setProductId(productId);
    order.setQuantity(quantity);
    order.setTotalPrice(product.getPrice() * quantity);
    
    orderRepository.save(order);
    emailService.sendOrderConfirmation(user.getEmail(), order);
    
    return Response.ok(order);
}
```

### Answers to Questions

**1. What happens if `user` is null?**

A `NullPointerException` will be thrown when trying to call `user.getEmail()`. The code doesn't check if the user exists before using it.

**2. What happens if `product` is null?**

Same problem - `NullPointerException` when accessing `product.getPrice()`.

**3. What happens if `quantity` is negative or zero?**

An order would be created with invalid quantity. Business rule violation.

**4. What happens if `product.getPrice()` returns null?**

`NullPointerException` when multiplying, or `NullPointerException` on the math operation.

**5. Should the email be sent before or after the transaction commits?**

**AFTER** - If the transaction fails after sending email, the user gets an email for an order that doesn't exist. The correct flow:

1. Begin transaction
2. Create order
3. Commit transaction
4. Send email (outside transaction)

---

## Hints (Read Before Answers)

Still stuck? Try these hints:

1. **Hint 1**: Look for places where we call methods on objects that might be null.
2. **Hint 2**: Think about what "invalid" values could be passed.
3. **Hint 3**: Consider what happens if the database save fails after the email is sent.

---

## Complete Refactored Solution

If you've tried and want to verify:

```java
@PostMapping("/orders")
public Response createOrder(Long userId, Long productId, Integer quantity) {
    // Validate inputs
    if (userId == null || productId == null || quantity == null || quantity <= 0) {
        return Response.status(400).entity("Invalid parameters").build();
    }
    
    // Get entities with null checks
    User user = userRepository.findById(userId);
    if (user == null) {
        return Response.status(404).entity("User not found").build();
    }
    
    Product product = productRepository.findById(productId);
    if (product == null) {
        return Response.status(404).entity("Product not found").build();
    }
    
    // Calculate total (with null check for price)
    if (product.getPrice() == null) {
        return Response.status(500).entity("Product price not set").build();
    }
    
    BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(quantity));
    
    // Create order
    Order order = new Order();
    order.setUserId(userId);
    order.setProductId(productId);
    order.setQuantity(quantity);
    order.setTotalPrice(totalPrice);
    
    // Save in transaction
    orderRepository.save(order);
    
    // Send email AFTER successful transaction
    emailService.sendOrderConfirmation(user.getEmail(), order);
    
    return Response.ok(order);
}
```

But wait - this is still in a single method! Imagine adding more logic. This is why we need layers.