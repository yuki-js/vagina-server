# Chapter 2: Exercise Answers

## Exercise 2.1: Layer Identification

### The Code to Analyze

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

---

## Answers

### Which lines are Repository layer?

Lines: **1, 2, 6**

- `userRepository.findById()` - data access
- `productRepository.findById()` - data access
- `orderRepository.insert()` - data access

### Which lines are Service layer?

Lines: **4, 5**

- `product.getPrice().multiply()` - calculation logic
- Creating the Order object and setting fields

### Which lines are UseCase layer?

Lines: **3, 7**

- `if (!user.isActive())` - authorization check
- `throw new SecurityException()` - authorization decision
- The orchestration of the flow

---

## Hints

Still stuck? Try these hints:

1. **Hint 1**: Ask yourself "what question does this answer?"
   - `findById()` answers "get me data from DB" → Repository
   - `multiply()` answers "how do I calculate" → Service
   - `if (!user.isActive())` answers "is this user allowed?" → UseCase

2. **Hint 2**: Can the code be reused in multiple places?
   - Finding a user by ID → Repository (generic)
   - Calculating price → Service (could be reused)
   - Checking if user can order → UseCase (business rule)

3. **Hint 3**: Would a different business rule change this code?
   - If we allow inactive users to order → Service stays same, UseCase changes

---

## Complete Separation

If you've tried and want to verify:

### UseCase Layer

```java
public class OrderUseCase {
    @Inject UserRepository userRepository;
    @Inject ProductRepository productRepository;
    @Inject OrderService orderService;
    
    public OrderResult createOrder(Long userId, Long productId, Integer quantity) {
        // Validation (part of flow)
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        
        // Authorization (USECASE - business rule)
        if (!user.isActive()) {
            throw new SecurityException("User account is not active");
        }
        
        // Delegate to service
        return orderService.createOrder(user, product, quantity);
    }
}
```

### Service Layer

```java
public class OrderService {
    @Inject OrderRepository orderRepository;
    
    public OrderResult createOrder(User user, Product product, Integer quantity) {
        // HOW to create order (reusable technical logic)
        BigDecimal totalPrice = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        
        Order order = new Order();
        order.setUserId(user.getId());
        order.setProductId(product.getId());
        order.setQuantity(quantity);
        order.setTotalPrice(totalPrice);
        
        orderRepository.insert(order);
        
        return new OrderResult(order);
    }
}
```

### Repository Layer

```java
public interface OrderRepository {
    Optional<User> findById(Long id);
    Optional<Product> findById(Long id);
    void insert(Order order);
}
```

Notice: Repository layer doesn't have business logic, just data access.