# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew :migration:build

# Run
./gradlew :migration:bootRun

# All tests
./gradlew :migration:test

# Single test class
./gradlew :migration:test --tests "org.migration.sharepoint.SomeTest"
```

Java version is managed via `mise` (`mise.toml` pins Java 25). Run `mise install` if the JDK is missing.

## Project: SP Migrator

Web application that migrates data from SharePoint (via Microsoft Graph API) to relational and NoSQL databases dynamically, with Quartz-scheduled jobs and a web UI for management.

The React frontend (Vite + shadcn/ui) is built separately and placed in `migration/main/resources/static/`. The Spring Boot process serves both the REST API and the static assets from a single container.

## Project structure

Multi-module Gradle project. The root `build.gradle.kts` declares plugins with `apply false`; the actual application lives entirely in the `:migration` submodule.

**Non-standard source layout** — there is no `src/` wrapper. Source roots are configured explicitly in `migration/build.gradle.kts`:

```
migration/
  main/java/          → application source
  main/resources/     → application.properties, static/ (SPA assets)
  test/java/          → tests
```

All dependencies are declared in `gradle/libs.versions.toml` and referenced via `libs.*` accessors.

## Architecture

**SPA routing:** `SpaForwardController` forwards all unmatched GET requests (no file extension) to `index.html` using `forward:` (not redirect).

**Security:** `ServerSecurityConfig` is stateless (no session). SPA assets, auth endpoints, actuator health, and error handling are public; Swagger and application endpoints require admin authority (`ROLE_ADMIN` or normalized `ADMIN`). Security headers are hardened (HSTS, CSP, COOP, CORP, Referrer-Policy, Permissions-Policy).

**Rate limiting:** `RateLimitingFilter` uses Bucket4j (100 req/min per IP, in-memory). Toggle with `security.rate-limit.enabled=false`. API routes return JSON 429; non-API routes redirect to `/?error_code=429`.

**Error handling:** All errors flow through `HttpExceptionHandler` (`@RestControllerAdvice`) and are serialized as `DataObjectError` (record: `timestamp`, `status`, `error`, `code`, `message`, `path`, `traceId`). Domain exceptions extend `AppException` and carry an `ErrorCode` enum that maps to `HttpStatus`.

**MDC logging:** Log pattern includes `requestId`, `userEmail`, `clientIp` MDC fields. `RequestUtil.getClientIP` resolves real IP honoring `X-Forwarded-For`.

## Domain (to be built)

**Persistence (SQLite via JPA):**
- `migration_jobs` — job configuration (Azure credentials, siteId, listId, target DB, connection string, table/collection, cron expression)
- `migration_logs` — execution history per job (status, timestamp, error message)

**Job lifecycle:**
- Jobs are created/edited/deleted at runtime via REST API and persisted to SQLite
- On startup, all jobs stored in SQLite are reloaded into the Quartz `RAMJobStore`
- `cron = null` means manual-only execution

**Migration flow per job:**
1. Authenticate against Microsoft Graph API using Azure App Registration (client credentials)
2. Fetch SharePoint list items with automatic pagination
3. Infer schema from list fields
4. Write to target DB (PostgreSQL, MySQL, or MongoDB) — full replace (truncate + reimport)

**Key env vars:**
- `APP_PORT` — server port (default `8080`)
- `security.rate-limit.enabled` — toggle rate limiting (default `true`)
