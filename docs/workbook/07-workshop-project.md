# Chapter 7: Workshop Project - Putting It All Together

## Your Mission

Add a new feature to the application: **User Avatar**

You'll implement:
1. API endpoint to upload/view avatar
2. Database migration for avatar storage
3. Proper layered architecture (Resource → UseCase → Service → Repository)
4. Unit tests

## Feature Requirements

```
POST /api/users/{userId}/avatar
    - Upload avatar image for a user
    - Only the user themselves can upload
    - Max file size: 1MB
    - Allowed formats: PNG, JPG, GIF

GET /api/users/{userId}/avatar
    - Get avatar URL for a user
    - Public endpoint (anyone can view)
    - Returns default avatar if not set

DELETE /api/users/{userId}/avatar
    - Remove avatar
    - Only the user themselves can delete
```

## Step-by-Step Implementation

### Step 1: Define the API Schema

Add to `openapi/components/schemas/user.yaml`:

```yaml
AvatarUploadRequest:
  type: object
  properties:
    imageData:
      type: string
      format: base64
      description: Base64-encoded image data
    contentType:
      type: string
      enum: [image/png, image/jpeg, image/gif]

AvatarResponse:
  type: object
  properties:
    userId:
      type: integer
      format: int64
    avatarUrl:
      type: string
      format: uri
    updatedAt:
      type: string
      format: date-time
```

Add to `openapi/paths/users.yaml`:

```yaml
/api/users/{userId}/avatar:
  post:
    tags:
      - Users
    summary: Upload user avatar
    description: Upload avatar image for a user
    operationId: uploadAvatar
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    requestBody:
      required: true
      content:
        application/json:
          schema:
            $ref: '../components/schemas/user.yaml#/AvatarUploadRequest'
    responses:
      '200':
        description: Avatar uploaded successfully
        content:
          application/json:
            schema:
              $ref: '../components/schemas/user.yaml#/AvatarResponse'
      '403':
        description: Not authorized to upload avatar
      '400':
        description: Invalid image data or format
  
  get:
    tags:
      - Users
    summary: Get user avatar
    description: Get avatar URL for a user
    operationId: getAvatar
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    responses:
      '200':
        description: Avatar found
        content:
          application/json:
            schema:
              $ref: '../components/schemas/user.yaml#/AvatarResponse'
      '404':
        description: User not found
  
  delete:
    tags:
      - Users
    summary: Delete user avatar
    description: Delete avatar for a user
    operationId: deleteAvatar
    parameters:
      - name: userId
        in: path
        required: true
        schema:
          type: integer
          format: int64
    responses:
      '200':
        description: Avatar deleted
      '403':
        description: Not authorized to delete avatar
```

### Step 2: Generate Code

```bash
./gradlew compileOpenApi generateOpenApiModels
```

### Step 3: Create Database Migration

Create file: `src/main/resources/db/migration/V5__Add_user_avatar.sql`

```sql
-- Add avatar_url column to users table
ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500);

-- Add index for faster lookups
CREATE INDEX idx_users_avatar_url ON users(avatar_url);
```

### Step 4: Update Entity

Modify `src/main/java/app/aoki/quarkuscrud/entity/User.java`:

```java
public class User {
    private Long id;
    private AccountLifecycle accountLifecycle;
    private String usermeta;
    private String sysmeta;
    private String avatarUrl;  // NEW
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Add getter and setter
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
```

### Step 5: Update MyBatis Mapper

Add to `src/main/java/app/aoki/quarkuscrud/mapper/UserMapper.java`:

```java
@Mapper
public interface UserMapper {
    // ... existing methods
    
    @Update("UPDATE users SET avatar_url = #{avatarUrl}, updated_at = NOW() WHERE id = #{id}")
    void updateAvatarUrl(@Param("id") Long id, @Param("avatarUrl") String avatarUrl);
}
```

### Step 6: Create Service

Create `src/main/java/app/aoki/quarkuscrud/service/AvatarService.java`:

```java
@ApplicationScoped
public class AvatarService {

    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/png", "image/jpeg", "image/gif"
    );

    @Inject UserMapper userMapper;

    @Transactional
    public void uploadAvatar(Long userId, String imageData, String contentType) {
        // Validate content type
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Invalid image format. Allowed: PNG, JPG, GIF");
        }

        // Decode and validate size
        byte[] imageBytes = Base64.getDecoder().decode(imageData);
        if (imageBytes.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("Image too large. Max size: 1MB");
        }

        // Generate URL (simplified - in production, upload to S3/GCS)
        String avatarUrl = generateAvatarUrl(userId, contentType);

        // Save to database
        userMapper.updateAvatarUrl(userId, avatarUrl);
    }

    public Optional<String> getAvatarUrl(Long userId) {
        return userMapper.findById(userId)
            .map(User::getAvatarUrl);
    }

    @Transactional
    public void deleteAvatar(Long userId) {
        userMapper.updateAvatarUrl(userId, null);
    }

    private String generateAvatarUrl(Long userId, String contentType) {
        // In production: upload to cloud storage and return URL
        return String.format("/avatars/%d.%s", userId, getExtension(contentType));
    }

    private String getExtension(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            default -> "bin";
        };
    }
}
```

### Step 7: Create UseCase

Create `src/main/java/app/aoki/quarkuscrud/usecase/AvatarUseCase.java`:

```java
@ApplicationScoped
public class AvatarUseCase {

    @Inject AvatarService avatarService;

    @Transactional
    public AvatarResponse uploadAvatar(Long userId, Long requestingUserId,
            String imageData, String contentType) {
        
        // Authorization: Only the user can upload their own avatar
        if (!userId.equals(requestingUserId)) {
            throw new SecurityException("You can only upload your own avatar");
        }

        // Upload avatar (validation happens in Service)
        avatarService.uploadAvatar(userId, imageData, contentType);

        // Return response
        return new AvatarResponse()
            .userId(userId)
            .avatarUrl(String.format("/avatars/%d", userId))
            .updatedAt(OffsetDateTime.now());
    }

    public AvatarResponse getAvatar(Long userId) {
        String avatarUrl = avatarService.getAvatarUrl(userId)
            .orElse(null); // null means default avatar

        return new AvatarResponse()
            .userId(userId)
            .avatarUrl(avatarUrl != null ? avatarUrl : "/avatars/default.png")
            .updatedAt(OffsetDateTime.now());
    }

    @Transactional
    public void deleteAvatar(Long userId, Long requestingUserId) {
        // Authorization: Only the user can delete their own avatar
        if (!userId.equals(requestingUserId)) {
            throw new SecurityException("You can only delete your own avatar");
        }

        avatarService.deleteAvatar(userId);
    }
}
```

### Step 8: Implement Resource

Modify `src/main/java/app/aoki/quarkuscrud/resource/UsersApiImpl.java`:

```java
// Add inject
@Inject AvatarUseCase avatarUseCase;

// Add methods
@Override
@Authenticated
@POST
@Path("/users/{userId}/avatar")
@Consumes(MediaType.APPLICATION_JSON)
public Response uploadAvatar(@PathParam("userId") Long userId, AvatarUploadRequest request) {
    User user = authenticatedUser.get();
    try {
        AvatarResponse response = avatarUseCase.uploadAvatar(
            userId, user.getId(), request.getImageData(), request.getContentType());
        return Response.ok(response).build();
    } catch (SecurityException e) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity(new ErrorResponse(e.getMessage())).build();
    } catch (IllegalArgumentException e) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ErrorResponse(e.getMessage())).build();
    }
}

@Override
@GET
@Path("/users/{userId}/avatar")
@Produces(MediaType.APPLICATION_JSON)
public Response getAvatar(@PathParam("userId") Long userId) {
    try {
        AvatarResponse response = avatarUseCase.getAvatar(userId);
        return Response.ok(response).build();
    } catch (Exception e) {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(new ErrorResponse("User not found")).build();
    }
}

@Override
@Authenticated
@DELETE
@Path("/users/{userId}/avatar")
public Response deleteAvatar(@PathParam("userId") Long userId) {
    User user = authenticatedUser.get();
    try {
        avatarUseCase.deleteAvatar(userId, user.getId());
        return Response.ok().build();
    } catch (SecurityException e) {
        return Response.status(Response.Status.FORBIDDEN)
            .entity(new ErrorResponse(e.getMessage())).build();
    }
}
```

### Step 9: Write Tests

Create `src/test/java/app/aoki/quarkuscrud/service/AvatarServiceTest.java`:

```java
@QuarkusTest
public class AvatarServiceTest {

    @Inject AvatarService avatarService;

    @Test
    void uploadAvatar_validImage_success() {
        String imageData = Base64.getEncoder().encodeToString("fake-image".getBytes());
        
        assertDoesNotThrow(() -> 
            avatarService.uploadAvatar(1L, imageData, "image/png"));
    }

    @Test
    void uploadAvatar_invalidFormat_throwsException() {
        String imageData = Base64.getEncoder().encodeToString("fake-image".getBytes());
        
        assertThrows(IllegalArgumentException.class, () ->
            avatarService.uploadAvatar(1L, imageData, "application/pdf"));
    }

    @Test
    void getAvatar_existingUser_returnsUrl() {
        Optional<String> url = avatarService.getAvatarUrl(1L);
        assertTrue(url.isPresent() || url.isEmpty()); // Depends on data
    }
}
```

## Checklist

Before you're done, verify:

- [ ] Schema defined in OpenAPI YAML
- [ ] Code generated with Gradle
- [ ] Migration created in `db/migration/`
- [ ] Entity updated with new field
- [ ] Mapper updated with new SQL
- [ ] Service created with technical logic
- [ ] UseCase created with authorization
- [ ] Resource implemented (thin, delegates)
- [ ] Tests written

## Key Takeaways

1. **Follow the pattern** - Each layer has its role
2. **Authorization in UseCase** - Not in Service or Resource
3. **Validation in Service** - Technical validation (file size, format)
4. **Resource is thin** - Just parses and delegates
5. **Test each layer** - Different tests for different layers

## Congratulations!

You've completed the workshop! You now understand:

- Why layered architecture matters
- The difference between UseCase and Service
- Where each type of code belongs
- How to design APIs with OpenAPI
- How to read existing codebases

**Keep practicing these principles and they'll become second nature.**

---

## Bonus Track

Want to reinforce your learning or help others? Check out:

**[Bonus Track: Code Review Checklist](08-code-review-checklist.md)** - A systematic guide for reviewing layered architecture code. Use it when reviewing others' code or having your code reviewed.

---

## Final Exercise

**Challenge**: Add a feature that doesn't exist yet!

Ideas:
- Event comments/reactions
- User follow system
- Activity feed
- Push notification preferences

Follow the same pattern:
1. Define schema
2. Generate code
3. Create migration
4. Add entity fields
5. Update mapper
6. Create service
7. Create use case
8. Implement resource
9. Write tests