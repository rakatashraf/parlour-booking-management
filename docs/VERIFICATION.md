# Verification and outstanding work

The repository is a buildable end-to-end development implementation. Commercial launch still depends on external production credentials, hosting, release signing, policy review, and live-device validation.

## Current automated verification

- Spring Boot/Maven verification passes on Java 17.
- Booking invariants pass against a real MySQL 8.4 service in GitHub Actions.
- Android `assembleDebug` and `lintDebug` pass, and CI produces an installable debug APK artifact.
- Firestore Security Rules pass emulator tests for public verified parlours, owner access to owned pending resources, customer/owner booking access, admin-only operational data, and denial of direct client writes.
- Firestore projection is event-driven after committed SQL mutations, with a five-minute full reconciliation fallback.
- Native owner JPEG/PNG selection and Firebase Storage upload are implemented; uploads automatically create gallery records that propagate to Firestore.

## External/live validation still required

- Publish the repository Firestore rules and indexes to the live Firebase project.
- Provide a backend Google/Firebase service identity, set `FIREBASE_ENABLED=true`, and validate live Firestore, FCM and Storage behavior.
- Production Spring Boot/MySQL hosting behind HTTPS, database backups, restore drills, and shared ingress/rate limiting.
- SSLCommerz merchant credentials plus sandbox callback/refund testing before enabling live payments.
- Physical-device usability/accessibility testing and production privacy/disclosure review for Analytics, Crashlytics, notifications and location.
- Embedded Maps UI requires a Maps SDK/API key if desired; external Google Maps directions already work without embedding.
- Release signing/Play Store configuration requires a private release keystore and store credentials.

## Product enhancements not required for the core booking flow

- Owner walk-in booking UI and richer owner reporting.
- Password recovery requires a transactional email/SMS delivery provider and recovery-token policy.
- Larger marketplaces should replace the current 500-candidate location search with indexed geospatial querying/pagination.
- Multi-instance production notification delivery should use coordinated outbox claims or a queue.
- Staff splitting and overnight appointments are intentionally outside the current booking model.

Keep live payment credentials and production customer traffic disabled until the external/live validation items above are completed.
