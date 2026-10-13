# External API documentation

Comprehensive integration guide for frontend and API consumers. The backend base URL is supplied by the operator; all paths below are relative to it. Use HTTPS outside local development. Maintainer/build/configuration details are in [Internal documentation](INTERNAL.md).

Swagger is available locally at `/swagger-ui.html`; the OpenAPI JSON is `/v3/api-docs`. The production profile disables both by default. An operator can explicitly enable them with `SPRINGDOC_API_DOCS_ENABLED=true` and `SPRINGDOC_SWAGGER_UI_ENABLED=true`. Missing routes on an older running backend require updating that backend; this document describes the current source contract.

## Authentication and account API

This repository contains the Spring Boot backend only. The client must wire the following API flows into its screens. All requests and tokens require HTTPS outside local development. Supported sign-in methods are username/password and Google, plus automatic guest sessions. See [Internal documentation](INTERNAL.md) for environment variables and a PowerShell smoke test.


## Start anonymously, upgrade later

1. On first use, call `POST /api/auth/guest` with an empty body. Save the credentials returned and use `user.id` as the actor ID in the existing receipt API.
2. Use `Authorization: Bearer <accessToken>` on receipt, friend and account requests. Existing receipt `userId` query parameters and `/api/users/{userId}/friends` paths must match the authenticated user. The previous unauthenticated `POST /api/users` has been removed and denied. Actor IDs in query parameters must match the token; participant IDs inside receipt payloads can identify other users.
3. To register this guest, call `/register` or `/google` **with the guest access token**. The backend upgrades the same database user: receipts, participants, and friends keep their IDs. Save the new credentials; guest sessions are revoked.
4. If the chosen identity already belongs to a different account, upgrade returns 409. Offer sign-in separately and keep the guest credentials until the user chooses. There is no automatic merge into an existing account.
5. On future launches, use refresh rather than creating another guest. A guest cannot recover access after losing its tokens or after the 30-day session expires; prompt users to register to preserve access.

Successful guest/registration/sign-in/refresh responses:

```json
{
  "accessToken": "...",
  "refreshToken": "...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": {
    "id": 1, "username": "alice", "email": "alice@example.com",
    "displayName": "Alice", "status": "ACTIVE",
    "emailVerified": false, "passwordEnabled": true, "googleLinked": false
  }
}
```

Access JWTs expire in 15 minutes. Refresh tokens rotate on every use and expire with the original session after 30 days. Serialize refresh calls and atomically replace the stored refresh token. Reuse of an old token returns 401. Only hashes of refresh and email/nonce tokens are stored. Each API request checks the backing session, so logout immediately revokes even otherwise unexpired JWTs.

Keep access tokens in memory where practical. Persist refresh tokens in OS secure storage for native apps; browser deployments should use a BFF with secure HttpOnly cookies if persistence is required. This API accepts bearer headers and JSON refresh tokens, not cookies. CSRF is disabled for that stateless transport; do not add cookie authentication without CSRF protection. Never log credentials or tokens.

## Endpoints

All paths below start with `/api/auth`. JSON request bodies are shown. Responses are credential objects unless stated otherwise.

| Method/path | Body / behavior |
|---|---|
| POST `/guest` | No body; creates a guest (201). |
| POST `/register` | `{ "username":"alice", "password":"at least twelve characters", "email":"alice@example.com", "displayName":"Alice" }` (201). Optional guest bearer token upgrades in place. Username: 3–32 ASCII letters/digits/underscore, case-insensitive. Password: 12–72 characters and no more than 72 UTF-8 bytes, hashed with BCrypt. |
| POST `/login` | `{ "username":"alice", "password":"..." }` or `{ "username":"alice@example.com", "password":"..." }`. The existing `username` field accepts either identifier, case-insensitively, with surrounding whitespace trimmed. Generic 401 for incorrect credentials. |
| POST `/refresh` | `{ "refreshToken":"..." }`; no bearer header required. Do not send an expired bearer header. |
| POST `/logout` | Authenticated, current session only, 204. Clear locally stored tokens. |
| POST `/logout-all` | Authenticated, revokes all sessions including this one, 204. |
| GET `/me` | Authenticated, returns the account object. |
| PUT `/me` | Authenticated, `{ "displayName":"Alice" }`; returns updated account. |
| DELETE `/me` | Authenticated, disables the account and revokes all sessions, 204. This is deactivation, not erasure: shared receipt records remain. |
| POST `/password/change` | Authenticated, `{ "currentPassword":"...", "newPassword":"..." }`, 204; all sessions revoked, sign in again. |
| POST `/email/send-verification` | Authenticated, no body, 202. Sends verification token to the account's email (one hour). |
| POST `/email/verify` | `{ "token":"..." }`, 204; token is single-use. |
| POST `/password/forgot` | `{ "email":"alice@example.com" }`, 202 regardless of account existence. Sends a 15-minute reset token only for active password accounts with verified email. |
| POST `/password/reset` | `{ "token":"...", "password":"..." }`, 204; revokes all sessions and outstanding reset tokens. |
| POST `/google/nonce` | No body, returns `{ "nonce":"..." }` (five minutes). Use the same optional bearer token as for `/google`. |
| POST `/google` | `{ "idToken":"...", "nonce":"..." }`. Pass the server nonce to Google Identity Services when requesting the ID token. The backend checks Google's signature, issuer, expiry, audience, authorized party, and the single-use nonce. |

Google sign-ins create registered accounts automatically when the identity is new. Sending an authenticated account's bearer token explicitly links the verified identity to that account. Existing linked identities cannot be replaced by these endpoints. Email alone is never sufficient to link a Google identity to an existing user. Google-only accounts continue to sign in with Google: adding a password to an already registered Google-only account is not implemented. Password change requires an existing password. Profile editing currently changes only the display name.

Send email verification after password registration and show a resend option. Unverified email is not a recovery method. Users can continue using receipts before verification. A 401 on an authenticated call means refresh once, then show sign-in if refresh fails. 400 means request validation failure; invalid credentials or expired/consumed verification tokens return 401; 409 identity conflict; 429 wait before retrying; 503 provider configuration or delivery is unavailable.

## Operational scope

Local abuse controls allow 30 auth requests per IP/minute, 10 password attempts per account/minute (shared across username and email; unknown identifiers are limited separately), and 3 email requests per address/minute. They are bounded, in-memory, per-instance limits and reset on restart. Configure shared rate limits at the gateway before scaling out. The app uses the socket address, not untrusted forwarded headers; configure trusted proxy handling explicitly for deployment.

Expired auth records are cleaned hourly. Tokens and passwords are not returned in account objects. OpenAPI includes the bearer authentication scheme. `./gradlew.bat test` includes real Spring Security integration tests; Google/SMTP delivery is mocked in those tests, so a configured staging smoke test is still required.

Provider references: [Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect), [Spring JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).

## Postman tests

See [Postman guide](INTERNAL.md#postman-verification) for the automated account suite and separate Google/email collections with manual verification checkpoints. The core suite needs only the database and JWT configuration; live Google and SMTP verification require their respective provider setup.

Password login by email still requires a password-enabled active account. Email verification is required for password recovery, not for password login. Google-only accounts continue to use Google sign-in.

## Receipt visibility

Draft receipts are private to their owner, even when friends have already been added as participants. Participants can list and read only finalized or settled receipts. Reopening a finalized receipt removes participant visibility until it is finalized again; direct draft reads by non-owners return 403.

## Receipt and contact integration

Use this guide as the product/API contract for building the frontend. The API supports guest accounts, username/password authentication, Google OIDC sign-in, and revocable JWT sessions. Read [Authentication and account API](EXTERNAL.md#authentication-and-account-api) for the complete account contract.

## Instructions for the frontend implementer

Build a polished, responsive Splitwise-style web frontend for this existing Spring Boot backend. Use **React, TypeScript, and Vite**. If a frontend already exists, extend it instead of replacing its setup. Integrate the documented API with automatic guest onboarding and the supported account flows. Do not modify backend code or invent endpoints.

Prioritize a clear, usable receipt-editing experience over visual novelty. Use accessible forms, keyboard-friendly controls, loading/empty/error states, confirmation before destructive actions, and mobile-friendly layouts. Keep API calls in a typed client module rather than directly scattered through components.

Before implementing, inspect the running API's Swagger contract at `http://localhost:8080/swagger-ui.html` when it is available. If a detail in this document conflicts with Swagger, use Swagger's request/response schema and report the discrepancy rather than guessing.

### Definition of done

- A new user can start as a guest without filling out a registration form, manage people, and complete the receipt lifecycle. Keep the authenticated account and credentials in centralized session state.
- Guests can upgrade through password registration or Google sign-in while retaining their receipt history. Existing users can sign in, sign out, verify email, recover/change passwords, edit their display name, and deactivate their account.
- The receipt list includes all receipts the current user owns, plus finalized and settled receipts they participate in, without duplicates. Drafts are visible only to the owner.
- A draft receipt can be saved and reloaded without losing participants, items, allocations, or manual tax/fee/tip overrides.
- Owners can independently choose a split method for each item, review server-calculated balances, finalize, reopen, settle, and delete when allowed.
- The UI shows useful API validation errors. Editing requires ownership and draft status; participants always have a read-only view.
- Payment processing, delivered contact invitations, and real-time collaboration are not implemented. Adding an invited contact records contact data; it does not send an invitation.

## Product

This is a Splitwise-style receipt splitter. An owner creates a receipt, adds line items and people, assigns each item using a split method, reviews the calculated result, and finalizes it. A finalized receipt may be reopened for corrections, or marked settled when everyone is done.

People can be normal account users, invited contacts (known email, no account yet), or name-only placeholders. All three can be participants on a receipt.

Owners can view their receipts in every status. Participants can view only `FINALIZED` and `SETTLED` receipts; friendship or draft participation does not grant access to a `DRAFT`. Reopening a receipt immediately hides it from participants until it is finalized again. Only the owner can modify the receipt, its participants, items, or allocations, calculate its split, change its status, or delete it. Removing a participant immediately removes that user's access unless they are the owner.

## Local setup

- Backend base URL: `http://localhost:8080`
- Frontend development URL allowed by CORS: `http://localhost:5173`
- Interactive API reference while the backend is running: `http://localhost:8080/swagger-ui.html`
- Machine-readable OpenAPI contract: `http://localhost:8080/v3/api-docs`
- Money values are JSON numbers. Display them as currency with two decimal places. The UI may show an instant non-authoritative preview, but the backend's `calculate` result is the final authority.
- Dates are `LocalDateTime` strings such as `2026-09-30T18:00:00`.

## Authentication and caller identity

On first use, call `POST /api/auth/guest` to obtain an account and credentials. On later launches, restore the session using `/api/auth/refresh`; do not silently replace a lost guest session with a new account. Offer registration to preserve access before guest credentials expire or are lost.

Send `Authorization: Bearer <accessToken>` on receipt, contact, and protected account requests. Every receipt request still requires `?userId=<authenticated account id>`. Use the signed-in account's ID even when viewing somebody else's receipt; never substitute the receipt's `ownerId`. Contact routes use that same identity in `/api/users/{userId}/friends`. The backend verifies these actor IDs against the JWT and rejects mismatches. IDs inside participant/allocation payloads identify participants and may refer to other people.

There is no development user picker. `POST /api/users` is removed. Get the current account from `GET /api/auth/me`. There is no general user-directory endpoint.

Use the guest bearer token when calling `/api/auth/register`, or for both `/api/auth/google/nonce` and `/api/auth/google`, to upgrade in place. Replace the old credentials with the returned credentials. A 409 means the identity cannot be linked; preserve the guest session and offer a separate sign-in without promising a data merge.

Serialize refresh requests and replace rotating refresh tokens atomically. Do not attach an expired bearer token to refresh or anonymous sign-in requests. After logout/account switching, clear account-specific cached data and cancel pending writes. Guest logout loses access unless credentials for another session remain; explain this before discarding a guest session. See [Authentication and account API](EXTERNAL.md#authentication-and-account-api) for token storage, expiry, Google nonce handling, and recovery details.

## Receipt lifecycle

See [Balance and payment integration](EXTERNAL.md#balance-and-payment-integration) for the complete participant payment, reversal, name, and balance contracts.

```
DRAFT --calculate--> DRAFT       (calculates preview amounts)
DRAFT --finalize--> FINALIZED
FINALIZED --reopen--> DRAFT      (allows revisions)
FINALIZED --settle/all participants paid--> SETTLED
SETTLED --unsettle--> FINALIZED   (when external debt exists)
```

- Only owners may edit drafts or calculate their splits.
- Only owners may finalize, reopen, or settle receipts, subject to the status transitions above.
- Only owners may delete drafts or finalized receipts without recorded positive non-owner payments.
- Unsettle recorded payments before reopening/editing/deleting paid receipts. Unsettle keeps the split finalized and restores outstanding debt. Owner-only/zero-debt receipts remain settled.
- Finalizing runs the server-side calculation as part of the action.

## Primary editor API: save the whole draft

For the receipt editor, prefer this endpoint instead of making one request per item/allocation.

`PUT /api/receipts/{receiptId}/draft?userId={currentUserId}`

It replaces all editable draft state atomically: receipt details, participants, items, and allocations. If any part is invalid, nothing is saved. The response is the detailed receipt.

Example request (current user/owner ID `3`; `participantUserIds` excludes the owner):

```json
{
  "merchantName": "Bulk Bistro",
  "purchaseDate": "2026-09-30T18:00:00",
  "discount": 0,
  "tax": 1,
  "fee": 0,
  "tip": 3,
  "currency": "USD",
  "participantUserIds": [4],
  "adjustmentAllocations": [
    { "adjustmentType": "TIP", "userId": 4, "amount": 3 }
  ],
  "items": [
    {
      "name": "Pizza",
      "quantity": 1,
      "unitPrice": 10,
      "allocations": [
        { "userId": 3, "allocationType": "EXACT", "inputValue": 6 },
        { "userId": 4, "allocationType": "EXACT", "inputValue": 4 }
      ]
    },
    {
      "name": "Salad",
      "quantity": 1,
      "unitPrice": 5,
      "allocations": [
        { "userId": 4, "allocationType": "EXACT", "inputValue": 5 }
      ]
    }
  ]
}
```

The item total is calculated by the backend as `quantity * unitPrice`. The receipt subtotal and total are calculated from its items and adjustments; do not send them in the bulk-save request. `adjustmentAllocations` replaces **all** existing manual overrides during bulk save: include the current overrides whenever the receipt uses any, or send an empty array to use proportional tax/fee/tip again.

## Allocation types

Each item can use one allocation type across its allocation entries.

| Type | `inputValue` means | Example |
| --- | --- | --- |
| `EXACT` | currency amount | Alex 6, Sam 4 for a $10 item |
| `EQUAL` | any positive value; use `1` | two entries split a $10 item $5/$5 |
| `PERCENTAGE` | percentage | `60` and `40` split a $10 item $6/$4 |
| `SHARES` | relative shares | `2` and `1` split a $9 item $6/$3 |

`EXACT` entries cannot exceed the item total and `PERCENTAGE` entries cannot exceed 100. The server checks that every item is fully allocated when it calculates/finalizes. Tax, fees, tips, and discounts are distributed proportionally across the participants' allocated item amounts unless manual overrides are supplied. The server handles cent rounding.

## Essential endpoints

### Users and contacts

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/auth/guest` | Create a guest session; response includes credentials and `user.id` |
| `GET` | `/api/auth/me` | Get the authenticated account; all account endpoints are documented in [Authentication and account API](EXTERNAL.md#authentication-and-account-api) |
| `POST` | `/api/users/{userId}/friends` | Add a contact: `{ "displayName": "Sam" }` for placeholder, or include `email` for invite/find-existing-user; `{ "username": "sam" }` links an existing account |
| `GET` | `/api/users/{userId}/friends` | List contacts usable in participant selection |
| `DELETE` | `/api/users/{userId}/friends/{friendUserId}` | Remove only the directional friend link |

Friend response:

```json
{ "id": 8, "userId": 4, "displayName": "Sam", "email": null, "status": "PLACEHOLDER" }
```

User statuses are `ACTIVE`, `GUEST`, `INVITED`, `PLACEHOLDER`, and `DISABLED`. Guest and active accounts can authenticate; the other statuses cannot. Invited contacts and placeholders are not claimable accounts through the current registration flow. A contact relationship is directional: adding Sam to Alex's list does not automatically add Alex to Sam's.

### Receipts

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/receipts?userId={currentUserId}` | Create initial receipt with at least one item |
| `GET` | `/api/receipts?userId={currentUserId}` | List all owned receipts plus finalized/settled receipts including the user as a participant, without duplicates |
| `GET` | `/api/receipts/{receiptId}?userId={currentUserId}` | Owner: any status. Participant: finalized/settled only. Returns items, allocations, participants, and saved balances |
| `PUT` | `/api/receipts/{receiptId}?userId={currentUserId}` | Update draft merchant, purchase date, charges, and currency |
| `PUT` | `/api/receipts/{receiptId}/draft?userId={currentUserId}` | Preferred full draft save |
| `PUT` | `/api/receipts/{receiptId}/adjustment-allocations?userId={currentUserId}` | Replace manual tax, fee, and tip overrides; an empty array restores all-proportional allocation |
| `POST` | `/api/receipts/{receiptId}/calculate?userId={currentUserId}` | Calculate preview for Review Split |
| `POST` | `/api/receipts/{receiptId}/finalize?userId={currentUserId}` | Calculate and finalize |
| `POST` | `/api/receipts/{receiptId}/reopen?userId={currentUserId}` | Reopen finalized receipt |
| `POST` | `/api/receipts/{receiptId}/settle?userId={currentUserId}` | Mark finalized receipt settled |
| `POST` | `/api/receipts/{receiptId}/unsettle?userId={currentUserId}` | Reverse all participant payments; returns full receipt |
| `POST` | `/api/receipts/{receiptId}/participants/{participantId}/settle?userId={currentUserId}` | Mark one participant paid; owner only |
| `DELETE` | `/api/receipts/{receiptId}?userId={currentUserId}` | Delete draft or unpaid finalized receipt |

There are also granular endpoints for add/update/delete item, participant, and allocation. Use them only for small immediate interactions; the full draft save is the normal editor persistence API. Swagger lists the complete request/response schemas.

Every receipt request requires a valid bearer token and the authenticated account's ID in the `userId` query parameter. That user becomes the owner when creating a receipt. For existing receipts, reads permit the owner in any status or a participant only after finalization, and every mutation requires the supplied ID to match the owner, including granular endpoints and calculate. The authentication layer verifies the supplied actor ID before the receipt service applies owner/participant permissions.

### Manual tax, fee, and tip allocation

By default, omit `adjustmentAllocations` and tax/fee/tip are allocated proportionally. To override one or more charges, submit exact amounts using either the bulk draft's optional `adjustmentAllocations` field or the dedicated endpoint above:

```json
[
  { "adjustmentType": "TAX", "userId": 3, "amount": 2.00 },
  { "adjustmentType": "FEE", "userId": 4, "amount": 1.00 },
  { "adjustmentType": "TIP", "userId": 4, "amount": 3.00 }
]
```

For every overridden type, the amounts must add up exactly to the receipt's tax, fee, or tip. Types omitted from the array stay proportional. The detailed receipt response returns saved overrides as `adjustmentAllocations`.

## Response shapes the UI needs

Detailed receipt responses include:

```json
{
  "id": 2,
  "merchantName": "Bulk Bistro",
  "purchaseDate": "2026-09-30T18:00:00",
  "subtotal": 15.00,
  "discount": 0.00,
  "tax": 1.00,
  "fee": 0.00,
  "tip": 3.00,
  "total": 19.00,
  "currency": "USD",
  "status": "DRAFT",
  "ownerId": 3,
  "adjustmentAllocations": [
    { "id": 11, "adjustmentType": "TIP", "participantId": 4, "userId": 4, "amount": 3.00 }
  ],
  "items": [
    {
      "id": 4,
      "name": "Pizza",
      "quantity": 1.00,
      "unitPrice": 10.00,
      "total": 10.00,
      "allocations": [
        {
          "id": 4,
          "itemId": 4,
          "participantId": 3,
          "allocationType": "EXACT",
          "inputValue": 6.0000,
          "finalAmount": 6.00
        },
        {
          "id": 5,
          "itemId": 4,
          "participantId": 4,
          "allocationType": "EXACT",
          "inputValue": 4.0000,
          "finalAmount": 4.00
        }
      ]
    },
    {
      "id": 5,
      "name": "Salad",
      "quantity": 1.00,
      "unitPrice": 5.00,
      "total": 5.00,
      "allocations": [
        {
          "id": 6,
          "itemId": 5,
          "participantId": 4,
          "allocationType": "EXACT",
          "inputValue": 5.0000,
          "finalAmount": 5.00
        }
      ]
    }
  ],
  "participants": [
    { "id": 3, "userId": 3, "finalOwedAmount": 6.40 },
    { "id": 4, "userId": 4, "finalOwedAmount": 12.60 }
  ]
}
```

`POST calculate` and `POST finalize` return a compact split view with `participants` (including `displayName`, `username`, `settled`, and `finalOwedAmount`) and flattened item `allocations`. Use it to render the review screen. Reload the detailed receipt after a state-changing action if the editor needs the complete object again.

Derive `isOwner` from `receipt.ownerId === currentUserId` and `canEdit` from `isOwner && receipt.status === 'DRAFT'`. There is no separate permissions field in the response. Participants receive the same detailed receipt shape and can render the saved `finalOwedAmount` and item `finalAmount` values directly without calling a write endpoint.

## Error handling

Controller validation and domain errors normally use this shape:

```json
{
  "timestamp": "2026-09-30T18:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Percentage allocations must total 100",
  "path": "/api/receipts/2/calculate",
  "fieldErrors": { "currency": "Currency must be a 3-letter ISO code" }
}
```

Show `message` as the general error. For editor validation, attach `fieldErrors[field]` near the matching input when present. Expect 400 for invalid input/lifecycle actions or a missing/invalid `userId`, 403 for receipt reads by users who are neither owner nor participant or writes by non-owners, 404 for missing resources, and 409 for duplicate users/friendships. If a receipt is reopened as a draft or participant access is revoked while it is open, handle the next GET's 403 by clearing its cached details and returning to the refreshed receipt list. On 401 from a protected request, refresh once and retry after successful refresh; if refresh fails, show sign-in. Handle 429 with a retry delay and 503 as unavailable provider configuration or delivery. Security-layer errors may return a minimal or empty body, so provide an HTTP-status fallback instead of assuming `message` or `fieldErrors` exists.

## Frontend implementation priorities

1. Build a typed API client around the OpenAPI schema or these documented shapes.
2. Keep one canonical editable receipt-draft object in client state.
3. Enable autosave/debounced-save through `PUT /draft` and an explicit Save action only when `canEdit`; prevent overlapping saves and show a saving/error state. Cancel pending saves when switching users or leaving the editable view.
4. Owners calculate drafts through `POST /calculate`; participants render saved amounts from GET. Use client calculations only for non-authoritative previews.
5. Gate every mutation control and handler on ownership and the permitted status: edit/calculate/finalize for owner drafts, Reopen/Settle for owner finalized receipts, and Delete for owner draft/finalized receipts. Participant views have no mutation controls or write requests.
6. Treat placeholders/invites exactly like active users in participant pickers, but show a small status label.
7. Append the authenticated account ID to every receipt request and use it in contact paths through the typed API client. Attach the bearer token and centralize refresh/retry handling. Never retry indefinitely or replay pending writes after switching accounts.

## Suggested component boundary

- `api/`: typed fetch client, API error parser, DTO types.
- `state/`: authenticated account, session/refresh coordination, and editable draft state.
- `pages/`: account/onboarding, verification/recovery, receipt list, people, editor, and review/detail screens.
- `components/receipt/`: receipt details, item list/editor, allocation editor, adjustment allocation editor, participant picker, totals, and lifecycle actions.

Keep editing state in the parent receipt editor and use the detailed receipt response as the source of truth after every successful save. Never derive a participant's final owed amount locally for persistence or final display.

## Add people by username or email
Use `POST /api/users/{currentUserId}/friends` with bearer authentication and JSON:

- Existing account by username: `{ "username": "sam" }`.
- Existing account by email: `{ "email": "sam@example.com" }`.
- New invited person: `{ "email": "sam@example.com", "displayName": "Sam" }`.
- Placeholder: `{ "displayName": "Sam" }`.

Username and email matching is case-insensitive. Send only one identifier; sending both returns 400. Blank identifiers are treated as absent. Display name is optional when linking an existing account; its existing name is preserved. A name is still required to create a placeholder or invited person. Unknown username returns 404 and never creates a new account. Unknown email with a display name preserves the existing invited-person behavior; without a name it returns 400. Duplicate contacts return 409; adding yourself returns 400. Success returns 201 with the existing FriendResponse shape (`id`, `userId`, `displayName`, `email`, `status`).

For one frontend input labeled "Username or email", trim its value and send it as `email` when it contains `@`, otherwise as `username`. Keep the existing add-placeholder flow. Refetch the People list after success; use the returned user's ID for receipt participation.

## Balance and payment integration

## Scope and accounting
The backend is implemented in this repository. Implement the UI in the frontend repository using these contracts. The receipt owner is assumed to have paid the receipt. Each non-owner participant owes their `finalOwedAmount` to that owner. An owner's own share never creates debt. Only FINALIZED receipts and unsettled participants contribute to outstanding balances; DRAFT and SETTLED receipts do not. Calculation previews do not create debt.

Keep gross incoming and outgoing debts visible. Offsets are informational only: `netBalance = owedToYou - youOwe`. Positive means the person owes the current user; negative means the current user owes the person; zero means balanced (possibly with outstanding debts both ways). Never treat a zero net balance as settled. No multiway offset or settlement is implemented.

Money is grouped by receipt currency. Never sum or offset different currencies; no conversion is provided. Totals are per currency. Decimal JSON numbers represent two-decimal money; use a decimal library or integer cents rather than floating point arithmetic.

## Authentication
Send `Authorization: Bearer <accessToken>` on every request. Obtain the current user ID from the existing authenticated account response. The actor ID must match the token subject. Never use a person's ID as the actor ID. User routes authorize the `{userId}` path; receipt routes authorize the `userId` query parameter. Existing authentication and error handling are described in AUTHENTICATION.md and FRONTEND_HANDOFF.md.

## Read balances
`GET /api/users/{userId}/balances` returns HTTP 200:

```json
{
  "userId": 1,
  "currencies": [{
    "currency": "USD",
    "totalOwed": 10.00,
    "totalOwing": 6.00,
    "netBalance": 4.00,
    "people": [{
      "userId": 2,
      "username": "bob",
      "displayName": "Bob",
      "owedToYou": 10.00,
      "youOwe": 6.00,
      "netBalance": 4.00
    }]
  }]
}
```

`totalOwed` means money owed TO the current user. `totalOwing` means money the current user owes. Currency blocks are sorted by currency and people by user ID. Only people with outstanding gross debt in at least one direction appear, including those with zero net. Empty `currencies: []` means no outstanding debt; show an empty state, not an error. Contacts need not still exist in the user's friend list for a debt to appear. Display names are included, so no lookup is required.

## Settle a person across receipts
`POST /api/users/{userId}/balances/people/{personUserId}/settle` with no body returns HTTP 200:

```json
{
  "personUserId": 2,
  "currencies": [{"currency": "USD", "settledAmount": 10.00, "participantCount": 2}],
  "affectedReceiptIds": [101, 102]
}
```

This records ALL outstanding payments that person owes the current user across ALL currencies and finalized receipts owned by the current user, in one transaction. It never settles money the current user owes that person. In the $10/$6 example, this records $10 received; after refresh the current user still owes $6 and the net is -$6. The UI must not describe this action as paying or settling the $4 offset. It records payment; it does not transfer funds.

Enable “Mark all received” only when the person's `owedToYou > 0`. If that person appears in multiple currency blocks, the button still settles all their currencies. A confirmation dialog should list every gross incoming amount across currencies and explain that outgoing debt remains. Disable duplicate clicks during the request. Success: invalidate/refetch balances, receipt lists, and affected open receipt details. Use the result for a receipt/count summary, not as a replacement balance payload. Repeating the request after payment returns empty `currencies` and `affectedReceiptIds`; this is successful and safe. Unknown users return 404; settling oneself returns 400. Payments can be reversed per receipt using the unsettle endpoint below. There is no bulk person undo, partial payment, currency filter, or multiway settlement endpoint.

## Receipt participant settlement
`POST /api/receipts/{receiptId}/participants/{participantId}/settle?userId={currentUserId}` with no body returns the existing full ReceiptResponse. Only the receipt owner can confirm payment. `participantId` is the receipt participant record ID, NOT the user ID. A draft cannot be settled (400); a missing participant/receipt returns 404; a non-owner returns 403. Repeated settlement of an existing participant succeeds, including on a settled receipt.

The existing `POST /api/receipts/{receiptId}/settle?userId=...` remains available for marking every participant settled together; it accepts only FINALIZED receipts.

ReceiptResponse participants now contain `{id, userId, finalOwedAmount, settled, username, displayName}`. ReceiptSplitResponse participants now contain `{participantId, userId, username, finalOwedAmount, settled, displayName}`. `finalOwedAmount` remains the original share after payment; do not zero it in the UI. Use `settled` for payment status, with DRAFT displaying “Draft” rather than “Unpaid”. On finalization the owner and zero-share participants automatically count as settled. A receipt automatically becomes SETTLED as soon as all participants are settled, including receipts with no external debt. Otherwise it remains FINALIZED even when some participants have paid.

Finalized receipts with any paid non-owner positive share cannot be reopened or deleted (400). To edit a paid receipt, unsettle it first, then reopen it. Hide/disable direct edit/delete controls until payments have been reversed. Draft calculated amounts are previews and must not be included in balances.

## Suggested UI
For each currency, render cards “You are owed”, “You owe”, and “Net balance”. Render person rows with display name, gross incoming, gross outgoing, and a directional net label. For Bob show “Bob owes you $10”, “You owe Bob $6”, “Net: Bob owes you $4”. Negative net: “Net: You owe Bob $4”; zero: “Net: Balanced”. Keep an incoming settlement button available even when the net is zero or negative. Receipt details show each participant's original share and settlement badge; only owners get payment controls.

Handle initial loading, refetching, empty balances, authentication expiry (401), forbidden access (403), validation/state errors (400), missing resources (404), and request failures. Do not show successful settlement until the server confirms it. On ambiguous network failures refetch before retrying; repeating a settlement is safe. Refetch on return/focus to reflect payments recorded by other receipt owners. No pagination or push updates exist for balances.

## Frontend acceptance checks
- Bob owes $10 and you owe $6: gross totals $10/$6, net +$4.
- Multiple people: gross totals equal the sums of rows, net equals their difference.
- Same person in USD and CAD: separate totals/nets; settlement confirmation includes both currencies.
- Zero net with debt both ways: show both debts and retain incoming settlement button.
- Settle Bob across two receipts: only Bob's incoming shares disappear; other participants and outgoing debt remain.
- Settling the last unpaid participant changes receipt status to SETTLED.
- Drafts, owner shares, zero shares, and settled participants do not inflate balances.
- Repeated settlement succeeds without duplicating recorded amounts.
- Refresh receipt details and balances after finalization/reopening/deletion/settlement.


## Unsettle a receipt and show participant names
`POST /api/receipts/{receiptId}/unsettle?userId={currentUserId}` takes no body and returns the full ReceiptResponse. Only the owner may reverse recorded payments (403 for other users); missing receipts return 404 and drafts return 400. Works for fully settled and partially paid FINALIZED receipts. All positive non-owner shares become unpaid, restoring their original debts to balances. The owner and zero shares remain settled. The receipt returns to FINALIZED unless it has no external debt, in which case it remains SETTLED. Split amounts and allocations are unchanged. Repeating the request is safe.

Add an owner-only "Unsettle receipt" control with confirmation explaining that ALL recorded payments on this receipt are reversed, including payments recorded through "Mark all received" across receipts. Refetch balances, receipt lists, and receipt details on success. This changes records only; it does not refund or transfer funds. After unsettlement a receipt with external debt can be reopened for editing using the existing reopen endpoint. No per-participant or person-wide unsettlement endpoint is provided.

For participant names in receipt list/detail/create/add-participant responses, use `participant.displayName`, falling back to `participant.username` only if the display name is empty. Split preview/finalize responses also include both fields. Do not rely on the current user's friend list: another participant may not be their contact. IDs are keys, not display labels; "Person #4" is only a last-resort fallback for old server responses. Balances already include both name fields. The frontend code is not in this repository, so update its rendering to consume these new fields.

Additional acceptance checks: settling and then unsettling restores incoming and outgoing balances for both owner and participants; unrelated receipts keep their payment states; repeating unsettlement changes nothing; drafts and non-owner attempts are rejected; receipt details and split previews display the participant's display name.

## Explicit unsettle endpoint contract

### Request
```http
POST /api/receipts/101/unsettle?userId=1
Authorization: Bearer <accessToken>
Accept: application/json
```
No request body. `receiptId` is the receipt ID. Required `userId` is the authenticated current user's ID, which must also be the receipt owner. Do not pass the debtor ID. No Content-Type header is required for this bodyless request.

### Successful response
HTTP **200 OK**, `Content-Type: application/json`. Returns the full receipt, using the same schema as GET /api/receipts/{receiptId}. Example for a receipt whose owner paid $16, with Bob's $10 share being reversed:
```json
{
  "id": 101,
  "merchantName": "Cafe",
  "purchaseDate": "2026-10-12T18:00:00",
  "subtotal": 16.00,
  "discount": 0.00,
  "tax": 0.00,
  "fee": 0.00,
  "tip": 0.00,
  "total": 16.00,
  "currency": "USD",
  "status": "FINALIZED",
  "ownerId": 1,
  "items": [{
    "id": 201,
    "name": "Dinner",
    "quantity": 1,
    "unitPrice": 16.00,
    "total": 16.00,
    "allocations": [
      {"id": 301, "itemId": 201, "participantId": 11, "allocationType": "EXACT", "inputValue": 6.00, "finalAmount": 6.00},
      {"id": 302, "itemId": 201, "participantId": 12, "allocationType": "EXACT", "inputValue": 10.00, "finalAmount": 10.00}
    ]
  }],
  "participants": [
    {"id": 11, "userId": 1, "finalOwedAmount": 6.00, "settled": true, "username": "alice", "displayName": "Alice"},
    {"id": 12, "userId": 2, "finalOwedAmount": 10.00, "settled": false, "username": "bob", "displayName": "Bob"}
  ],
  "adjustmentAllocations": []
}
```

The endpoint reverses ALL positive non-owner participant payments on this receipt, including a partially settled receipt. It preserves original amounts and allocations. Owner/zero-share participants remain settled; no-external-debt receipts remain SETTLED. Repeating the request succeeds without further changes. Other receipts and reverse-direction debts are untouched. After success refetch balances and receipt lists and replace cached receipt detail with the returned receipt.

### Errors
- 400: draft receipt, missing/invalid required userId, or invalid receiptId.
- 401: missing, expired, or invalid authentication.
- 403: userId differs from the token subject, or the authenticated user is not the receipt owner.
- 404: receipt does not exist.
Use existing generic API error handling; authentication errors may be generated by the security layer rather than the application's error DTO.

### API discovery
The route is implemented in ReceiptController. Documentation does not install it into a running server. If the backend you are calling has no `/api/receipts/{receiptId}/unsettle` entry in `/v3/api-docs`, rebuild and restart it from the updated source, then check that the frontend and Swagger point to that same backend. Do not wire reversal to `/reopen`: unsettle restores debt while keeping the split finalized; reopen transitions to DRAFT and removes it from balances. Check the running backend version when a route is missing.

## Granular receipt endpoints

All routes below begin with `/api/receipts/{receiptId}` and require `?userId={currentUserId}` plus bearer authentication. All mutations require the receipt owner. Draft-only editing follows the same calculation invalidation rules as full draft save. Participant record IDs and user IDs are different identifiers.

| Method | Suffix | Body / result |
| --- | --- | --- |
| POST | `/participants` | `{ "userId": 2 }`; 201 participant response |
| DELETE | `/participants/{participantId}` | No body; 204; remove allocations first; owner cannot be removed |
| POST | `/items` | `{ "name": "Dinner", "quantity": 1, "unitPrice": 10 }`; 201 item response |
| PUT | `/items/{itemId}` | Same item fields; 200 item response |
| DELETE | `/items/{itemId}` | No body; 204; receipt must retain at least one item |
| POST | `/items/{itemId}/allocations` | `{ "participantId": 12, "allocationType": "EXACT", "inputValue": 10 }`; 201 allocation response |
| PUT | `/items/{itemId}/allocations/{allocationId}` | `{ "allocationType": "EXACT", "inputValue": 10 }`; 200 allocation response |
| DELETE | `/items/{itemId}/allocations/{allocationId}` | No body; 204 |

Participant responses include `id`, `userId`, `finalOwedAmount`, `settled`, `username`, and `displayName`. Allocation responses include `id`, `itemId`, `participantId`, `allocationType`, `inputValue`, and `finalAmount`. Render displayName directly, falling back to username; people do not need to be in the viewer's contact list.
