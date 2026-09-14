# Verification and outstanding work

This is a development preview, not a complete commercial launch.

## Recorded evidence

Before the build environment became unavailable on 2026-09-14, the local Spring Boot test command completed successfully: **6 tests, 0 failures, 0 errors**. Tests covered concurrent reservations, authoritative prices, idempotency, guest/owner isolation, cancellation slot release, holidays/blocks, and premature review/completion rejection.

The source was recovered from the session into GitHub after the environment interruption. Repository CI must validate the recovered snapshot independently. H2 MySQL mode does not substitute for real MySQL validation.

The web admin JavaScript syntax check passed locally. Browser interaction tests were not completed. The local Android build was interrupted before its final result could be read. No APK or lint success is claimed from that local attempt.

## Remaining requirements

- Original AI Studio frontend source and visual matching.
- Firebase Console access and live FCM, Storage, Analytics and Crashlytics validation. No cloud settings have been changed.
- Production Spring Boot/MySQL hosting and reachable HTTPS API.
- SSLCommerz credentials, sandbox lifecycle/refund tests and production enablement.
- Real MySQL migration/concurrency tests.
- Device/emulator testing, accessibility review, offline data cache and rotation/process-death recovery.
- Embedded Maps UI and gallery/photo-upload screen.
- Owner walk-in UI, automatic slot synchronization and full owner reporting.
- Full category/banner editing, pagination, system settings and richer admin analytics.
- Guest history recovery and owner password recovery.
- Distributed rate limiting, coordinated outbox claims and measured load performance.
- Release signing and backup/restore verification.

Existing bookings remain reserved when owner schedules change, but the schedule editor does not warn about those bookings. Search limits its candidate set to 500 verified parlours before distance filtering; larger datasets require indexed geographic queries and pagination. Search-performance targets have not been measured.

Keep live payments and production customer data out of this preview until integration, deployment and security validation are completed.
