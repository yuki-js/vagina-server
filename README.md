# Quarkus CRUD Template

![Build](https://img.shields.io/badge/gradle-8.x-02303a?logo=gradle&labelColor=0b1724)
![Quarkus](https://img.shields.io/badge/quarkus-3.x-b326ff?logo=quarkus&labelColor=111)
![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)

Opinionated Quarkus 3 starter. Schema-first OpenAPI, PostgreSQL, MyBatis, JWT, observability, generated clients. Clone the repo, run `./gradlew quarkusDev`, and you get a working stack.

## Features

- **Contracts**
  - Modular OpenAPI sources under `openapi/`
  - Custom compiler (`compileOpenApi`) that bundles the spec and feeds SmallRye OpenAPI
  - OpenAPI Generator producing REST interfaces, DTOs with Bean Validation, and swagger-request-validator tests
  - TypeScript fetch client packaged via npm `pack`
  - RFC 7807 Problem Details for semi-normal responses (e.g., `ProfileMissing`)
- **Persistence**
  - PostgreSQL 15 through Quarkus Dev Services or external DBs
  - Flyway migrations under `src/main/resources/db/migration`
  - MyBatis mappers for explicit SQL control
- **Operations**
  - SmallRye JWT for verification + token issuing
  - SmallRye OpenAPI + Swagger UI exposing the compiled contract
  - SmallRye Health, Micrometer Prometheus, JSON logging, and CORS presets
- **Delivery**
  - Dev UI with info extension enabled
  - Jib building distroless Java 21 images with OCI labels
  - Compiled `openapi.yaml` baked into runtime artifacts and client bundles

## Core stack

- Quarkus 3, RESTEasy Reactive, CDI
- Java 21 toolchain
- PostgreSQL 15 (Dev Services and production-ready configs)
- Flyway, MyBatis
- SmallRye JWT / OpenAPI / Health
- Micrometer Prometheus registry
- JSON logging via `quarkus-logging-json`
- Swagger UI, Dev UI, Quarkus Info extension
- OpenAPI Generator 7.x, swagger-request-validator
- Jib container builds

## Getting started

```bash
./gradlew quarkusDev
```

Dev Services launches PostgreSQL, Flyway migrates, the OpenAPI compiler runs, generated sources refresh, Dev UI appears at `http://localhost:8080/q/dev-ui`, Swagger UI at `/q/swagger-ui`, metrics at `/q/metrics`, and health probes at `/healthz` (prod profile) or `/q/health` (dev profile).

To build and ship the same stack:

```bash
./gradlew clean build jib
```

This produces a distroless image (default `ghcr.io/yuki-js/quarkus-crud:${version}`) with OCI labels and the OpenAPI artifact baked in.

## Documentation

Read [docs/index.md](docs/index.md) for the full guide to development, schema, database, observability, deployment, and client workflows.

## License

Apache License 2.0
