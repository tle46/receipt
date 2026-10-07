# Receipt Split API

Spring Boot backend for shared receipts, item allocations, contacts, and account management. Requires Java 26 and PostgreSQL; use the included Gradle wrapper.

Supported accounts: automatic guests, username/password registration and login, Google OIDC sign-in, and guest upgrades that retain receipt data. JWT sessions support refresh rotation and immediate logout. Email verification and password recovery use SMTP.

## Documentation

- [Paid AWS deployment](docs/AWS_LIGHTSAIL.md): single-server Lightsail setup targeting US$7/month before extras.

- [Local setup](docs/LOCAL_SETUP.md): environment variables, startup, and a guest-session smoke test.
- [Authentication integration](docs/AUTHENTICATION.md): account endpoints, token lifecycle, guest upgrades, deployment constraints.
- [Frontend handoff](docs/FRONTEND_HANDOFF.md): frontend requirements, authenticated API usage, receipt lifecycle and payloads.
- [Database migration](docs/auth-migration.sql): additive PostgreSQL authentication schema for existing installations.

Run `./gradlew.bat test` on Windows (`./gradlew test` on Unix). Integration tests use H2 and mock Google/SMTP; they do not verify live provider credentials.

After starting the backend, open [Swagger UI](http://localhost:8080/swagger-ui.html). Use the Authorize button with an access token for protected endpoints. The [OpenAPI schema](http://localhost:8080/v3/api-docs) documents request and response shapes.

## Postman examples

See the [Postman guide](postman/README.md) for import instructions and execution order.

- [Account/authentication suite](postman/accounts-auth.postman_collection.json): automated guest upgrades, login, refresh, logout, passwords, profile, authorization, and deactivation tests; no external provider required.
- [Email recovery suite](postman/accounts-email-recovery.postman_collection.json): SMTP verification and password reset with manual token-entry checkpoints.
- [Google suite](postman/accounts-google.postman_collection.json): nonce-bound guest upgrade with a real Google ID token.
- Receipt lifecycle and bulk-draft collections create their own guest sessions and authenticate receipt requests.

Run against a disposable development database. Authentication collections pace requests to respect rate limits. Do not share exported variables containing passwords or tokens.

The repository contains the backend only; the frontend must implement the documented screens and sign-in integration.
