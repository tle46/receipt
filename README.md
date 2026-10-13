# Receipt Split API

Spring Boot backend for shared receipts, item splits, contacts, balances, and account authentication. Requires Java 26 and PostgreSQL.

## Build

### Windows

Build JAR
```powershell
.\gradlew.bat clean test bootJar
```

Directly start App
```powershell
.\gradlew.bat bootRun
```


### Linux

Make the wrapper executable:

```bash
chmod +x gradlew
```

Build JAR:

```bash
./gradlew clean test bootJar
```

Directly start app:

```bash
./gradlew bootRun
```

## Documentation

- [Internal guide](docs/INTERNAL.md): setup, example .env, architecture, deployment, and testing.
- [External API guide](docs/EXTERNAL.md): authentication, receipts, contacts, balances, and settlement.

Local API docs: `/swagger-ui.html` and `/v3/api-docs`. Both are disabled by default in the `prod` profile.
