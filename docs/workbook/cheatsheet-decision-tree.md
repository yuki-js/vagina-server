# Layer Decision Tree

## Which Layer Does This Code Go In?

Use this decision tree to find the right layer for any piece of code.

---

## Start Here

```
                    ┌─────────────────────┐
                    │ Is this an HTTP     │
                    │ endpoint?           │
                    │ (@Path, @GET, etc.) │
                    └──────────┬──────────┘
                               │
              ┌────────────────┴────────────────┐
              │ YES                                │ NO
              ▼                                    ▼
    ┌─────────────────┐              ┌─────────────────────────┐
    │    RESOURCE    │              │ Does this involve a     │
    │                 │              │ database operation?     │
    └─────────────────┘              │ (SELECT, INSERT, etc.)  │
                                     └────────────┬────────────┘
                                                  │
                                    ┌─────────────┴─────────────┐
                                    │ YES                       │ NO
                                    ▼                           ▼
                          ┌─────────────────┐       ┌─────────────────────────┐
                          │   REPOSITORY    │       │ Does this answer       │
                          │    (Mapper)     │       │ "Can they?" or          │
                          └─────────────────┘       │ "What happens first?"    │
                                                  └───────────┬─────────────┘
                                                              │
                                                ┌─────────────┴─────────────┐
                                                │ YES                       │ NO
                                                ▼                           ▼
                                      ┌─────────────────┐       ┌─────────────────────────┐
                                      │     USECASE     │       │ Does this answer       │
                                      │                 │       │ "How do we do X?"      │
                                      └─────────────────┘       │ (technical steps)       │
                                                                  └───────────┬─────────────┘
                                                                              │
                                                                    ┌─────────┴─────────┐
                                                                    │ YES               │ NO
                                                                    ▼                   ▼
                                                          ┌─────────────────┐   ┌─────────────────┐
                                                          │    SERVICE      │   │  Is it just     │
                                                          │                 │   │  data?          │
                                                          └─────────────────┘   └────────┬────────┘
                                                                                   │
                                                                         ┌──────────┴──────────┐
                                                                         │ YES                 │ NO
                                                                         ▼                     ▼
                                                               ┌─────────────────┐   ┌─────────────────┐
                                                               │    ENTITY       │   │  Is it an       │
                                                               │  (data only)    │   │  error type?    │
                                                               └─────────────────┘   └────────┬────────┘
                                                                                            │
                                                                                  ┌──────────┴──────────┐
                                                                                  │ YES                 │ NO
                                                                                  ▼                     ▼
                                                                        ┌─────────────────┐   ┌─────────────────┐
                                                                        │   EXCEPTION     │   │    SUPPORT      │
                                                                        │                 │   │  (utilities)    │
                                                                        └─────────────────┘   └─────────────────┘
```

---

## Question Reference

### "Is this an HTTP endpoint?"

**Yes if:**
- Has `@Path`, `@GET`, `@POST`, `@PUT`, `@DELETE` annotations
- Parses HTTP request parameters
- Returns HTTP responses
- Handles content type negotiation

**→ RESOURCE**

---

### "Does this involve a database operation?"

**Yes if:**
- Has MyBatis annotations (`@Select`, `@Insert`, etc.)
- Calls a mapper method
- Performs CRUD on an entity

**→ REPOSITORY/MAPPER**

---

### "Does this answer 'Can they?' or 'What happens first?'"

**Yes if:**
- Contains authorization checks (`if (!userId.equals(...))`)
- Throws `SecurityException`
- Orchestrates multiple services
- Defines the order of operations

**→ USECASE**

---

### "Does this answer 'How do we do X?'?"

**Yes if:**
- Contains technical implementation details
- Could be reused by multiple UseCases
- Transforms data without business rules
- Hashes passwords, calculates totals, etc.

**→ SERVICE**

---

### "Is it just data?"

**Yes if:**
- Contains only fields, getters, setters
- No business methods
- No `if` statements or calculations

**→ ENTITY**

---

### "Is it an error type?"

**Yes if:**
- Extends `Exception` or `RuntimeException`
- Represents a business error
- Has error data (userId, resourceId, etc.)

**→ EXCEPTION**

---

## Common Mistakes

| Mistake | Should Be | Why |
|---------|-----------|-----|
| SQL in Service | Mapper | Database operations belong in Mapper |
| Authorization in Service | UseCase | Authorization is a business rule |
| Business logic in Resource | UseCase | Resource should only delegate |
| Calculations in Entity | Service | Data objects shouldn't compute |
| HTTP handling in UseCase | Resource | HTTP is Resource responsibility |
| UseCase calling Service that just wraps Mapper | UseCase calling Mapper directly | Unnecessary indirection |

---

## Quick Quiz

Identify the layer for each:

1. `@Select("SELECT * FROM users WHERE id = #{id}")`
2. `if (!ownerId.equals(currentUser.getId())) throw new SecurityException()`
3. `user.setPasswordHash(bcrypt.hash(password))`
4. `@POST @Path("/users") public Response createUser(...)`
5. `public class User { private Long id; private String name; }`

<details>
<summary>Answers</summary>

1. **REPOSITORY/MAPPER** - It's a database query
2. **USECASE** - Authorization check ("Can they?")
3. **SERVICE** - Technical implementation (hashing)
4. **RESOURCE** - HTTP endpoint
5. **ENTITY** - Just data

</details>

---

*Use this tree whenever you're unsure where code belongs!*