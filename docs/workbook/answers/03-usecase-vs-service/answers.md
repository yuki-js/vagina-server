# Chapter 3: Exercise Answers

## Exercise 3.1: Separate the Concerns

### The Messy Code

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

---

## Answers

### Which authorization checks belong in UseCase?

- Line 6-7: `if (user == null)` → **UseCase** (or could be validation)
- Line 9-10: `if (product == null)` → **UseCase** (or could be validation)
- Line 12-13: `if (!user.isActive())` → **UseCase** (authorization - "can user make orders?")
- Line 15-16: `if (product.getStock() < quantity)` → **UseCase** (business rule)

### Which technical operations belong in Service?

- Line 18: `product.getPrice().multiply()` → **Service** (how to calculate)
- Lines 20-25: Creating Order object → **Service** (how to construct)
- Line 28: `orderRepository.insert()` → **Service** (how to save)
- Lines 30-31: Update stock → **Service** (how to update inventory)
- Line 34: Send notification email → **Service** (how to send)

### What would the Service methods look like?

```java
@ApplicationScoped
public class OrderService {
    
    @Inject OrderRepository orderRepository;
    @Inject ProductRepository productRepository;
    @Inject NotificationService notificationService;
    
    public Order createOrderEntity(Long userId, Long productId, Integer quantity, BigDecimal totalPrice) {
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setTotalPrice(totalPrice);
        
        orderRepository.insert(order);
        return order;
    }
    
    public void updateStock(Long productId, Integer quantity) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        product.setStock(product.getStock() - quantity);
        productRepository.update(product);
    }
    
    public BigDecimal calculateTotal(Product product, Integer quantity) {
        return product.getPrice().multiply(BigDecimal.valueOf(quantity));
    }
}
```

### What would the UseCase method look like?

```java
@ApplicationScoped
public class OrderUseCase {
    
    @Inject UserRepository userRepository;
    @Inject ProductRepository productRepository;
    @Inject OrderService orderService;
    
    public Order createOrder(Long userId, Long productId, Integer quantity) {
        // Step 1: Get entities (validation)
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Product not found"));
        
        // Step 2: Authorization (USECASE - business rules)
        if (!user.isActive()) {
            throw new SecurityException("User not active");
        }
        
        if (product.getStock() < quantity) {
            throw new IllegalStateException("Insufficient stock");
        }
        
        // Step 3: Calculate (HOW - delegate to Service)
        BigDecimal total = orderService.calculateTotal(product, quantity);
        
        // Step 4: Create order (HOW - delegate to Service)
        Order order = orderService.createOrderEntity(userId, productId, quantity, total);
        
        // Step 5: Update stock (HOW - delegate to Service)
        orderService.updateStock(productId, quantity);
        
        // Step 6: Send notification (post-action flow)
        notificationService.sendOrderCreatedEmail(user.getEmail(), order);
        
        return order;
    }
}
```

---

## Hints

Still stuck? Try these hints:

1. **Hint 1**: Ask "is this about 'can they do this?' or 'how do we do this?'"
   - "Can inactive users order?" → UseCase
   - "How do we calculate total?" → Service

2. **Hint 2**: Ask "would this change if business rules change?"
   - "Should we allow inactive users?" → UseCase (business decision)
   - "How do we save an order?" → Service (technical decision)

3. **Hint 3**: Would another UseCase ever need this logic?
   - "How to calculate total" → Yes, Service is correct
   - "Can this user order?" → No, specific to this business flow → UseCase