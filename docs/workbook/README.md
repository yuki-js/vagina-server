# Building Maintainable Backend: A Workbook

Welcome to this hands-on workshop for learning how to build maintainable backend applications using the Quarkus-CRUD template.

## What You Will Learn

By the end of this workbook, you will understand:

- **Why "just make it work" code becomes a nightmare** to maintain
- **The layered architecture pattern** and why separation of concerns matters
- **The difference between UseCase and Service layers** (this trips up many developers)
- **Where each type of code should live** in a well-structured project
- **Schema-first API development** and why it leads to better APIs
- **How to navigate and understand** existing codebase
- **How to review code** using layered architecture principles

## Who Is This For?

This workbook is designed for developers who:

- ✅ Can write Java code
- ✅ Know Docker and PostgreSQL basics
- ✅ Can set up their own development environment
- ❌ Put everything in controllers because "it works"
- ❌ Don't see the value in separating layers
- ❌ Don't know where to put different types of code
- ❌ Write code that's hard to read, modify, or debug

## Prerequisites

Before starting, ensure you have:

- Java 21 installed (`java -version`)
- Docker available (for PostgreSQL)
- Git basics knowledge
- Terminal comfort (cd, ls, ./gradlew)

## Setup

Clone the repository:

```bash
git clone https://github.com/yuki-js/quarkus-crud
cd quarkus-crud
```

Verify your environment:

```bash
./gradlew tasks --group openapi
```

You should see tasks like `compileOpenApi`, `generateOpenApiModels`.

## Workbook Structure

This workbook is organized into eight parts. We recommend completing chapters 1-7 in order, then the bonus track and cheat sheets whenever you need them.

| Chapter | Topic | Time |
|---------|-------|------|
| 1 | The Problem with "Just Make It Work" | 20 min |
| 2 | Layered Architecture | 15 min |
| 3 | UseCase vs Service | 25 min |
| 4 | File Organization | 20 min |
| 5 | Schema-First API Design | 30 min |
| 6 | Reading Code | 20 min |
| 7 | Workshop Project | 60 min |
| 8 | **Bonus Track**: Code Review Checklist | Reference |

## Cheat Sheets

Quick reference guides for daily use:

| Cheat Sheet | Purpose |
|-------------|---------|
| [cheatsheet-layers.md](cheatsheet-layers.md) | Layer responsibilities, code patterns, quick reference |
| [cheatsheet-decision-tree.md](cheatsheet-decision-tree.md) | Find the right layer for any code |
| [cheatsheet-antipatterns.md](cheatsheet-antipatterns.md) | Red flags and code smells by layer |
| [quick-reference.md](quick-reference.md) | Combined quick lookup guide |

## How to Use This Workbook

Each chapter follows this pattern:

1. **Concept** - Explanation with real examples
2. **Exercise** - Hands-on coding task
3. **Discussion** - Reflection questions
4. **Key Takeaways** - Summarized points

**Bonus Track** is designed as a reference - use it when doing code reviews, not necessarily in sequence.

## Getting Help

If you're stuck:

1. Check the `docs/workbook/answers/` folder for hints
2. Review the existing code in `src/main/java/app/aoki/quarkuscrud/`
3. Ask questions in the workshop channel

---

**Next**: [Chapter 1: The Problem with "Just Make It Work"](01-the-problem.md)