# Integration setup

## Firebase

Project: `parlour-booking-management`. Android package: `com.parlour.management`. Storage bucket: `parlour-booking-management.firebasestorage.app`.

The supplied Android client JSON identifies the app. It is not a service-account credential and does not grant Firebase Console or server administrator access. A connected Google account does not automatically provision backend credentials.

On the backend host use Application Default Credentials through an appropriately scoped service identity. For local development, an authorized ADC setup can be used. If using service-account JSON, keep it outside the repository and mount it read-only; set GOOGLE_APPLICATION_CREDENTIALS to that path. Do not send private keys in chat or commit them.

Set FIREBASE_ENABLED=true only after credentials, Cloud Messaging permissions and Storage access are ready. Compose defaults to false. Create an ignored Compose override for read-only credentials and the enabled setting. The bucket must exist and any billing requirements must already be met.

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
