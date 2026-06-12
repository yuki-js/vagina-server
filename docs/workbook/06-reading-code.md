# Chapter 6: Reading Code - The Underrated Skill

## Most Developers Can't Read Code

We're taught to write code. We're rarely taught to read it.

But in reality, you spend **more time reading code than writing it**:
- Debugging existing features
- Understanding how something works
- Adding new features to existing code
- Onboarding to a new project

This chapter teaches you how to efficiently read and understand a codebase.

## The Navigation Pattern

When you need to understand how something works, follow this pattern:

```
1. Find the ENTRY POINT (Resource layer)
2. Follow the CHAIN (UseCase → Service → Repository)
3. Understand the TERMINUS (Repository returns data)
```

## Step 1: Finding Entry Points

### How to Find a Specific Endpoint

If you want to understand `GET /api/users/{userId}`:

**Method 1: Search for the path**

```bash
grep -r "@Path.*users" src/
```

**Method 2: Look in resource files**

Look in `resource/` folder for files with `Api` suffix.

**Method 3: Check the generated API**

Look in `build/generated-src/openapi/src/gen/java/` for interfaces like `UsersApi`.
These files are generated under `build/` by Gradle, then added to the main source set for compilation.

### The Resource File Structure

Resource files typically:
- Have names ending with `ApiImpl.java` or `Controller.java`
- Are in the `resource/` package
- Implement generated interfaces
- Have `@Path`, `@GET`, `@POST` annotations

```java
@ApplicationScoped
@Path("/api")
public class UsersApiImpl implements UsersApi {
    // Entry point for /api/users/*
}
```

## Step 2: Following the Chain

Once you find the entry point, follow the chain:

```java
@GET
@Path("/users/{userId}")
public Response getUserById(@PathParam("userId") Long userId) {
    // This calls...
    return userService
        .findById(userId)  // → Service layer
        .map(user -> Response.ok(toUserPublicResponse(user)).build())
        .orElse(...);
}
```

So `getUserById` calls `userService.findById()`.

Let's look at the service:

```java
public Optional<User> findById(Long id) {
    // This calls...
    return userMapper.findById(id);  // → Repository layer
}
```

And the mapper:

```java
@Mapper
public interface UserMapper {
    Optional<User> findById(Long id);  // → Database
}
```

**Complete chain for this endpoint**: Resource → Service → Repository → Database

## Step 3: Understanding the Data Flow

### Common Write-Flow Pattern

```
HTTP Request
    ↓
Resource (parse parameters)
    ↓
UseCase (authorization)
    ↓
Service (technical execution)
    ↓
Repository (data access)
    ↓
Database
```

Not every endpoint uses every layer. In this repository, some simple read flows go directly from Resource to Service to Repository, while multi-step or authorization-heavy flows often include a UseCase.

### Common Response Flow

```
Database
    ↓
Repository (result set mapping)
    ↓
Service (transformation)
    ↓
UseCase (optional processing)
    ↓
Resource (format response)
    ↓
HTTP Response
```

## Practical Exercise: Trace a Feature

Let's trace the "Create Event" feature to understand how it works.

### Step 1: Find the Entry Point

Search for event creation:

```bash
grep -r "events" src/main/java/app/aoki/quarkuscrud/resource/
```

Found: `EventsApiImpl.java`

Look for POST endpoint:

```java
@Override
@Authenticated
@POST
@Path("/events")
@Consumes(MediaType.APPLICATION_JSON)
public Response createEvent(EventCreateRequest eventCreateRequest) {
    // ...
}
```

### Step 2: Follow the Chain

The implementation delegates to something. Let's find out:

```java
public Response createEvent(EventCreateRequest createEventRequest) {
    User user = authenticatedUser.get();

    try {
        Event event = eventUseCase.createEvent(user.getId(), createEventRequest);
        return Response.status(Response.Status.CREATED).entity(event).build();
    } catch (Exception e) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(new ErrorResponse("Failed to create event: " + e.getMessage()))
            .build();
    }
}
```

So it calls `eventUseCase.createEvent()`.

### Step 3: Look at the UseCase

```java
public Event createEvent(Long userId, EventCreateRequest request) throws Exception {
    String meta = objectMapper.writeValueAsString(request.getMeta());

    Event event = eventService.createEvent(
        userId,
        meta,
        eventService.toLocalDateTime(request.getExpiresAt())
    );

    String invitationCode = eventService.getInvitationCode(event.getId()).orElse(null);
    return toEventDto(event, invitationCode);
}
```

In the current codebase, this UseCase is mostly orchestration and DTO mapping. The deeper persistence workflow lives in the Service layer.

### Step 4: Look at the Service/Repository

The UseCase delegates to `eventService.createEvent()`. The Service then coordinates the lower-level writes through multiple mappers.

```java
public Event createEvent(Long initiatorId, String meta, LocalDateTime expiresAt) {
    eventMapper.insert(event);
    eventInvitationCodeMapper.insertIfInvitationCodeAvailable(code);
    eventAttendeeMapper.insert(initiatorAttendee);
    eventUserDataMapper.insert(initiatorUserData);
    return event;
}
```

So the practical chain for this feature is:

**Resource → UseCase → Service → Mapper(s) → Database**

## Finding Business Rules

Business rules are often in UseCase classes. Search for:

```java
// Authorization rules
if (!userId.equals(requestingUserId)) {
    throw new SecurityException("...");
}

// Validation rules
if (value < 0) {
    throw new IllegalArgumentException("...");
}

// State transitions
if (currentStatus == DELETED) {
    throw new IllegalStateException("...");
}
```

## Reading Mapper Annotations

Mapper interfaces contain SQL using Java annotations (@Select, @Insert, @Update, @Delete). Find them in:

```
src/main/java/app/aoki/quarkuscrud/mapper/
```

```java
@Mapper
public interface EventMapper {
    
    @Insert("INSERT INTO events (initiator_id, status, usermeta, sysmeta, expires_at, created_at, updated_at) " +
            "VALUES (#{initiatorId}, #{status}, #{usermeta}::jsonb, #{sysmeta}::jsonb, #{expiresAt}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Event event);
    
    @Select("SELECT * FROM events WHERE id = #{id}")
    Optional<Event> findById(Long id);
    
    @Update("UPDATE events SET status = #{status}, updated_at = NOW() WHERE id = #{id}")
    void update(Event event);
}
```

## Exercise 6.1: Trace the Code

**Task**: Understand how friendship creation works.

**Questions to answer**:
1. What's the endpoint path?
2. Which class handles the friendship logic after the API resource?
3. What authorization checks are made?
4. What Service methods are called?
5. What database tables are affected?

**Hint**: Start in `FriendshipsApiImpl.java`, then trace into `FriendshipUseCase.java` and `FriendshipService.java`.

## Key Takeaways

1. **Start at Resource layer** - entry points are there
2. **Follow the chain** - Resource → UseCase → Service → Repository
3. **Understand data flow** - both request and response
4. **Business rules are in UseCase** - search for SecurityException
5. **SQL is in Mapper annotations** - check mapper/ with @Select, @Insert, etc.

---

## What's Next?

Time to put it all together. The final chapter is a **workshop project** where you'll add a new feature using everything you've learned.

**[Next: Workshop Project](07-workshop-project.md)**