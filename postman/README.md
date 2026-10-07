# Postman collections

Import the desired `.postman_collection.json` file into Postman. Set **collection variables**, not environment variables with the same names; an active environment can override values captured by these collections. `baseUrl` defaults to `http://localhost:8080`.

Use a disposable development database. Account tests create records and deactivate their test account; deactivation does not erase users or placeholder contacts. Generated passwords and tokens are stored only in your local collection variables. Clear those values before exporting or sharing a run.

## Automated account and authentication tests

Import [accounts-auth.postman_collection.json](accounts-auth.postman_collection.json) and run the **entire collection in order**, with one iteration. No Google or SMTP credentials are required. Each run generates a unique username, email, and passwords, and captures its own IDs and tokens.

The core suite contains 43 requests with 76 assertions. It covers both username and email sign-in. Google and email recovery require the manual provider steps below.

The collection verifies:

- Anonymous access denial, guest creation, and guest profile retrieval.
- Password validation and duplicate registration rejection.
- Guest registration preserves the user ID and an existing receipt, and revokes guest credentials.
- Case-insensitive username and email login, and incorrect-password rejection.
- Refresh rotation and rejection of a consumed refresh token.
- Single-session logout leaves another session usable; logout-all revokes both sessions.
- Display-name updates persist.
- Password change requires the current password, revokes sessions, and disables the old password.
- Contact creation/list/removal, receipt cleanup, and actor-ID authorization on receipt/contact routes.
- Account deactivation revokes access/refresh tokens and prevents login.
- Malformed bearer tokens are rejected and account responses exclude credential fields.

The collection includes a 2.2-second delay before each request to stay below the API's 30 auth requests/IP/minute limit. Allow about two minutes. Do not run other authentication collections concurrently from the same IP. A 429 after earlier manual traffic may require waiting a minute and restarting the collection from its first request. Do not disable rate limiting to run it.

For CLI use with Newman installed:

```powershell
newman run postman/accounts-auth.postman_collection.json
```

To target another development server, add `--env-var baseUrl=http://localhost:8080` with its address. This is an intentional override of the collection default; do not supply credential-variable overrides.

## Email verification and recovery (manual checkpoints)

Import [accounts-email-recovery.postman_collection.json](accounts-email-recovery.postman_collection.json). Configure backend SMTP and set collection variable `recipientEmail` to a **fresh mailbox or alias you control**. A previously used email remains reserved even after account deactivation.

Run the numbered folders individually:

1. **Register and send verification**: creates a password account and sends a verification email. This folder clears old verification/reset tokens.
2. Copy the token from that email into collection variable `verificationToken`. Run **Verify email**: verifies it, rejects replay, checks the profile's verified flag, and requests a password reset.
3. Copy the reset email token into `resetToken`. Run **Reset password**: consumes the token, rejects replay, checks session revocation, verifies old/new password behavior, and deactivates the disposable account.

Do not run all folders unattended: the tokens must come from actual email delivery. Verification tokens expire after one hour and reset tokens after 15 minutes. Complete the verification folder while the initial access token is valid (15 minutes), or refresh it first. Missing SMTP configuration returns 503 and is a setup failure, not a passing verification test.

## Google sign-in (manual checkpoints)

Import [accounts-google.postman_collection.json](accounts-google.postman_collection.json). Configure backend `GOOGLE_CLIENT_ID` and a Google Identity Services frontend using that same OAuth client ID.

1. Run **Guest and bound Google nonce**. It saves guest credentials and `googleNonce`.
2. Request a real Google Identity Services ID token using that exact nonce; use a test Google identity not already linked to an account in this database. Paste the returned credential into `googleIdToken`. An OAuth access token is not suitable.
3. Within five minutes of nonce creation, run **Exchange real Google credential**. It checks invalid-token rejection and guest-bound nonce enforcement, upgrades the guest without changing its ID, checks old guest access and nonce replay rejection, reads the linked profile, then logs out.

Do not run the whole collection unattended. If the nonce expires, request another nonce and obtain a new matching Google credential. Missing Google configuration returns 503; a Google identity already linked to another account returns 409 instead of upgrading a new guest. Neither counts as a passing upgrade test. The collection leaves the Google account active so it can be used for later sign-in; it does not delete or disable a real Google-linked account.

No provider credentials, fabricated verification tokens, or backend verification bypasses are bundled in these files.

## Receipt collections

[receipt-api.postman_collection.json](receipt-api.postman_collection.json) exercises the receipt lifecycle. [bulk-draft-save.postman_collection.json](bulk-draft-save.postman_collection.json) exercises atomic draft saves and rollback. Each obtains guest tokens before receipt requests. Run from the top with a development database; the lifecycle collection can leave settled receipts.

For server configuration and endpoint details, see [Local setup](../docs/LOCAL_SETUP.md) and [Authentication integration](../docs/AUTHENTICATION.md).
