# Chapter 6: Exercise Answers

## Exercise 6.1: Trace the Code

### Questions and Answers

**1. What's the endpoint path?**

For the friendship creation flow, the endpoint is:

```
POST /api/users/{userId}/friendship
```

Related friendship endpoints in this repository also include `GET /api/friendships/{otherUserId}` and `GET /api/me/friendships/received`.

**2. Which class handles the friendship logic after the API resource?**

Start in `FriendshipsApiImpl.java`, then follow the creation flow into `FriendshipUseCase.java`.

The practical chain is:

`FriendshipsApiImpl` → `FriendshipUseCase` → `FriendshipService` → `FriendshipMapper`

`FriendshipUseCase` owns the flow, while `FriendshipService` performs the reusable technical friendship operations.

**3. What authorization checks are made?**

For the creation endpoint itself, the main access control is that the caller must already be authenticated.

Within the current creation flow:
- `FriendshipsApiImpl` requires authentication
- the sender user ID comes from the authenticated context, not from the request body
- `FriendshipUseCase` validates that the recipient user exists before creating or updating the friendship

There is no extra explicit sender-vs-recipient authorization rule in the create flow beyond using the authenticated user as the sender.

**4. What Service methods are called?**

In the current create-or-update flow, `FriendshipUseCase` calls these methods from `FriendshipService.java`:

```java
findByParticipants(senderId, recipientId);
createFriendship(senderId, recipientId, meta);
updateMeta(friendshipId, meta);
```

The UseCase also calls `UserService.findById(recipientId)` to verify that the target user exists.

**5. What database tables are affected?**

From the SQL in `FriendshipMapper.java` (using @Select annotation):

```sql
CREATE TABLE friendships (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL REFERENCES users(id),
    recipient_id BIGINT NOT NULL REFERENCES users(id),
    usermeta TEXT,
    sysmeta TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

Tables affected:
- `friendships` - main table for friendships
- `users` - referenced for validation (but not modified)

---

## How to Trace Any Feature

### Step 1: Find the Resource

Search in `resource/` folder for the API endpoint:

```bash
grep -r "friendships" src/main/java/app/aoki/quarkuscrud/resource/
```

### Step 2: Find the Friendship Logic

Look at the Resource class first, then trace the feature into `FriendshipUseCase.java`.

### Step 3: Follow the Chain

For this feature, follow the path from `FriendshipsApiImpl.java` into `FriendshipUseCase.java`, then into `FriendshipService.java`.

### Step 4: Check Authorization

Check the authenticated resource methods in `FriendshipsApiImpl.java` and any related friendship-handling code they call.

### Step 5: Find Data Access

In `FriendshipUseCase.java` and `FriendshipService.java`, look for calls into `FriendshipMapper`, such as:

```java
friendshipMapper.findByParticipants(userId1, userId2);
```

### Step 6: Check SQL

In `src/main/java/app/aoki/quarkuscrud/mapper/FriendshipMapper.java`:

```java
@Select("SELECT * FROM friendships WHERE sender_id = #{senderId} AND recipient_id = #{recipientId}")
Optional<Friendship> findBySenderAndRecipient(@Param("senderId") Long senderId, @Param("recipientId") Long recipientId);
```

---

## Hints

Still stuck?

1. **Hint 1**: Start at the Resource layer. Search for `@Path` annotations with the feature name.

2. **Hint 2**: Use `grep` to find all files that mention the feature:
   ```bash
   grep -r "friendship" src/
   ```

3. **Hint 3**: Look for the friendship-related class used after the Resource. For this feature, that means tracing into `FriendshipUseCase.java` first.

4. **Hint 4**: In the friendship flow, check authenticated resource methods and related UseCase logic for authorization behavior.

5. **Hint 5**: In `FriendshipService.java`, look for `@Transactional` methods for data modification logic.