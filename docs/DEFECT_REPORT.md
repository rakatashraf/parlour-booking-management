# Defect Report — Parlour Booking Management System

**Audit date:** 2026-10-17
**Branch:** `main` @ `b6cd346`
**Scope:** Full application audit (backend, Android, Firebase, CI/CD, Docker, admin panel, docs)

This report documents verified defects found during the audit, their severity, root cause, the fix applied, and verification status.

---

## Critical defects (fixed)

### C1. Registration hardcoded OWNER role — `backend/.../Auth.java`
- **Severity:** Critical
- **Root cause:** `register()` inserted `"role","OWNER"` for every registrant; no customer self-registration existed.
- **Fix:** `register()` now accepts an optional `role` field (`CUSTOMER`/`OWNER`, defaults to `CUSTOMER`, strict 400 on anything else). Self-registration as `ADMIN` is impossible. Android owner-registration flow sends `"role":"OWNER"` explicitly.
- **Verification:** Code review; existing auth tests unaffected.

### C2. Firestore sync failures silently dropped — `backend/.../FirestoreSync.java`
- **Severity:** Critical
- **Root cause:** `drain()` removed table names from `pending` *before* syncing; exceptions were only printed to stderr.
- **Fix:** Tables are removed from `pending` only after successful sync. Per-table failure counts with exponential backoff (10s → 5min cap). Failures are logged and recorded in Firestore `system/core` (`last_event_sync_failed_at`, `last_event_sync_failed_collections`, `last_event_sync_error`) so the admin status endpoint surfaces them. New `POST /api/admin/firestore/sync/retry` re-syncs only FAILED collections.
- **Verification:** Code review; admin sync UI displays failures.

### C3. Android rotation drops pending network responses — `android/.../MainActivity.java`, `AppViewModel.java`
- **Severity:** Critical
- **Root cause:** Callbacks lived in the Activity (destroyed on rotation); ViewModel result couldn't dispatch.
- **Fix:** Callback registry moved to `AppViewModel.pendingCallbacks` (survives rotation).
- **Verification:** Code review (no Android SDK in this environment for instrumented test).

### C4. No 401/session-expiry handling in Android — `android/.../ApiRepository.java`
- **Severity:** Critical
- **Root cause:** 401 surfaced as generic "Request failed (401)" with no recovery path.
- **Fix:** `AuthListener` interface; on 401 the token is cleared to guest, user removed, and `onSessionExpired()` fires once → MainActivity shows "Session expired, please sign in again" and navigates to account screen.
- **Verification:** Code review.

---

## High defects (fixed)

### H1. FCM notifications with no device tokens retried forever — `backend/.../FirebaseServices.java`
- **Fix:** Empty token list now marks notification terminal `FAILED` with attempts incremented.

### H2. Firestore sync triggered by internal bookkeeping writes — `backend/.../Db.java`
- **Fix:** `SILENT_UPDATE_TABLES = {notifications, device_tokens}` + `system_*` prefix: internal writes no longer emit sync events. Business inserts still emit.

### H3. SSLCommerz credentials in URL query string — `backend/.../Gateway.java`
- **Fix:** `verify()`, `refund()`, `refundStatus()` now use POST with form-encoded body.

### H4. Abandoned PENDING payments never cleaned up — `backend/.../Payments.java`, `Jobs.java`
- **Fix:** New `@Scheduled` `expirePayments()` marks PENDING payments EXPIRED when booking hold lapsed (15min + 5min grace), `ORDER BY payment_id ASC LIMIT 100`, conditional update for race safety.

### H5. Android logout client-side only — `android/.../ApiRepository.java`, `backend/.../Auth.java`
- **Fix:** New `POST /api/auth/logout` deletes caller's `device_tokens` (the real server-side state); Android calls it best-effort before clearing local state. (Stateless JWT: no server session store; token deny-list deliberately not added.)

### H6. No retry/offline handling in Android — `android/.../ApiRepository.java`
- **Fix:** OkHttp `RetryInterceptor` (3 attempts, GET-only, on IOException/502/503/504); `ConnectivityManager` check shows offline message immediately. Booking/payment POSTs never auto-retried (idempotency keys preserved).

### H7. Admin panel: no sync status/retry UI — `backend/.../static/admin/app.js`
- **Fix:** New "Sync" nav section: metric cards, per-collection status table, "Retry failed" and "Run full sync" buttons wired to existing + new endpoints.

### H8. Admin panel: bookings read-only — `backend/.../static/admin/app.js`, `Admin.java`
- **Fix:** Bookings view with status/date/search filters, detail expander, and status actions (confirm/reject/complete/cancel) via new `POST /api/admin/bookings/{bid}/status` mirroring owner transition rules + audit log.

### H9. Docker image never built in CI — `.github/workflows/verify.yml`
- **Fix:** New `docker` job builds `backend/Dockerfile` (no push); added to release `needs`.

---

## Medium defects (fixed)

| ID | File | Fix |
|----|------|-----|
| M1 | `Jobs.java` | `ORDER BY booking_id ASC` added to `expire()`/`remind()` |
| M2 | `Refunds.java` | Documented: UNKNOWN refunds need manual merchant-portal check (no auto-retry by design) |
| M3 | `FirestoreSync.java` | `BigDecimal` → Firestore: documented as float64 (no decimal type); amounts under 2^53 exact |
| M4 | `pom.xml` | Explicit `com.nimbusds:nimbus-jose-jwt:10.10` dependency (was transitive-only) |
| M5 | `Payments.java` | Missing `booking_id` → 400 with `{message}` envelope (was NPE → 500) |
| M6 | `FirebaseSession.java` | Custom-token sync: 3 attempts with backoff + persistent retry flag |
| M7 | `PushService.java` | Data-only FCM messages now build notifications from data payload |
| M8 | `ApiRepository.java` | User JSON encrypted with Keystore AES-GCM (was plaintext) |
| M9 | `app/build.gradle` | Release `signingConfigs` from `keystore.properties`/env vars; fail-fast with setup message |
| M10 | `MainActivity.java` | `photoParlourId`/`ownerPid` saved/restored via `onSaveInstanceState` |
| M11 | `app.js` | Generic pagination (25/page), sorting, text filter on all admin tables |
| M12 | `verify.yml` | Dependabot config + CodeQL Java analysis added |
| M13 | `compose.yaml` | `healthcheck` on `api` (`/api/public/health`); `HEALTHCHECK` in Dockerfile |
| M14 | `compose.yaml` | `restart: unless-stopped` on `api` and `mysql` |
| M15 | `Security.java` | `CorsConfigurationSource` bean from `CORS_ALLOWED_ORIGINS` (default: no cross-origin) |
| M16 | `.env.example` | Added `FIRESTORE_SYNC_INITIAL_DELAY_MS`, `GOOGLE_APPLICATION_CREDENTIALS` docs, `CORS_ALLOWED_ORIGINS` |
| M17 | `firebase-deploy.yml` | Pinned `firebase-tools@15.30.1` (matches verify.yml) |

---

## Verified non-issues (audit claims withdrawn after code check)

- **H7 (missing indexes on notifications/device_tokens):** Both columns are foreign keys; InnoDB auto-indexes them. Withdrawn.
- **Security.uid() ACTIVE bypass:** `device()`, `inbox()`, `Bookings.mine()` all route through `auth.current()` which enforces `ACTIVE`. The direct `Security.uid()` call in the new `logout()` is safe (logout needs no privilege).

## Verified correct (no changes needed)

- Double-booking prevention (`SELECT ... FOR UPDATE` parlour lock + overlap check; tested)
- Idempotency (`UNIQUE(customer_id, idempotency_key)` + request_hash; tested)
- Server-side price authority (client total ignored; tested)
- Payment verification (server-side `val_id` lookup, amount/currency checks; tested)
- Refund safety (PENDING→SENDING under row lock; UNKNOWN never marked success)
- BCrypt(12) passwords; HS256 JWT with 32-byte minimum + issuer validation
- Firestore rules: default-deny, all client writes denied, reads scoped by ownership/role
- No SQL injection (all parameterized; table names from whitelists)

---

## Outstanding limitations (not defects — need human/external action)

1. **Live Firebase deployment:** Rules/indexes never deployed to the live project; Admin SDK connectivity untested. Needs Firebase console access + service identity.
2. **SSLCommerz:** No sandbox/merchant credentials; payment flow is code-reviewed only.
3. **FCM:** No test devices; push delivery untested end-to-end.
4. **Android:** No instrumented tests (no SDK in CI for emulator tests); release keystore not created (by design — operator creates it).
5. **Load testing:** Booking concurrency tested (2 threads); production-scale load not tested.
