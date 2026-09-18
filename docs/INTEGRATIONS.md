# Integration setup

## Firebase

Project: `parlour-booking-management`. Android package: `com.parlour.management`. Storage bucket: `parlour-booking-management.firebasestorage.app`.

The supplied Android client JSON identifies the app. It is not a service-account credential and does not grant Firebase Console or server administrator access. A connected Google account does not automatically provision backend credentials.

On the backend host use Application Default Credentials through an appropriately scoped service identity. For local development, an authorized ADC setup can be used. If using service-account JSON, keep it outside the repository and mount it read-only; set GOOGLE_APPLICATION_CREDENTIALS to that path. Do not send private keys in chat or commit them.

Set FIREBASE_ENABLED=true only after credentials, Cloud Messaging permissions, Firestore and Storage access are ready. Compose defaults to false. Create an ignored Compose override for read-only credentials and the enabled setting. The bucket must exist and any billing requirements must already be met.

### Firestore core data model

The backend projects the core relational domain into Cloud Firestore using the same collection names as the SQL tables: `users`, `parlours`, `services`, `staff`, `staff_services`, `staff_schedules`, `business_hours`, `holidays`, `blocked_slots`, `cancellation_policies`, `parlour_images`, `bookings`, `booking_items`, `payments`, `ratings`, `notifications`, `complaints`, `admin_actions`, `categories`, `banners`, and `refund_requests`.

MySQL remains the transaction authority for slot collisions, payment state, and owner/admin mutations. Firestore is the authenticated cloud read model and real-time data surface. The backend Admin SDK owns Firestore writes and therefore uses IAM rather than mobile Security Rules.

Android requests `/api/firebase/custom-token` after its normal Spring Boot login or guest session. The backend mints a Firebase custom token whose UID is the existing numeric `user_id` and whose `role` claim matches the application role. This lets Firestore Security Rules authorize the same customer, owner, and admin identities without a second account system.

Sensitive fields `password_hash`, `request_hash`, and `idempotency_key` are deliberately excluded from the Firestore projection. Client writes are denied by Firestore rules.

The scheduled projection defaults to every five minutes. Administrators can force it with `POST /api/admin/firestore/sync` and inspect it with `GET /api/admin/firestore/status`. The projection writes `system/core` with schema and last-sync metadata.

Rules, composite indexes, schema documentation, and the Firebase project alias live in `firebase/`. Deploy with an authenticated Firebase CLI from that directory using `firebase deploy --only firestore,storage --project parlour-booking-management`. The default Firestore database must exist in Native mode before the first sync.
Device registration uses /api/device-token. FCM delivery retries up to ten attempts. Delivery is at-least-once, with possible duplicates after partial delivery or restart. Run one notification worker until coordinated claims are implemented. In-app history remains available when FCM is disabled.

Owner-authorized image uploads use /api/owner/parlours/{id}/upload with multipart field file. The backend checks type and dimensions, then returns a URL to save in parlour_images. Public bearer download URLs are intended for public business photos only. The Android preview accepts gallery URLs; a native image picker remains pending.

firebase/storage.rules denies direct client reads/writes. It has NOT been deployed. Admin SDK access is governed by IAM. Review existing bucket usage before changing project rules.

Analytics and Crashlytics SDKs are included in Android. Live delivery has not been verified. Configure appropriate disclosure and collection settings before launch.

Official references: [Android setup](https://firebase.google.com/docs/android/setup), [Admin SDK credentials](https://firebase.google.com/docs/admin/setup).

## SSLCommerz

Supply SSLCOMMERZ_STORE_ID, SSLCOMMERZ_STORE_PASSWORD, a public HTTPS PUBLIC_API_URL and SSLCOMMERZ_LIVE=false for sandbox development. Configure /api/payments/callback as the merchant IPN listener.

The backend verifies validation IDs server-to-server, comparing transaction ID, amount, currency and risk before confirming an appointment. Redirects alone never mark payment paid. Late payment receipts create refund-review records.

Refund submission is an explicit admin action. Persisted SENDING/UNKNOWN states prevent blind retries after ambiguous responses. Reconcile through the merchant portal and verify action. Cash refunds require manual handling. Only one refund request per payment is supported, so repeated partial refunds need further implementation.

Official reference: [SSLCommerz validation and refund API](https://developer.sslcommerz.com/doc/v4/).

## Maps and hosting

Location is requested after the customer taps Use current location. Manual area search remains available. Directions open an external Google Maps app/browser. The embedded Maps SDK and gallery visuals remain pending the original frontend design.

MySQL and Spring Boot hosting are separate from Firebase Android registration. No paid database, host, domain or merchant account was provisioned. A debug APK requires a reachable backend.

Production requires HTTPS, server-managed credentials, backups and shared ingress rate limiting. Current rate limiting is per process and network peer address. Admin is served from the API origin; separately hosted web clients require an explicit restricted CORS configuration.
