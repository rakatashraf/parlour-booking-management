Development preview for Android 8.0 and later.

Download Parlour-Booking-Debug.apk on your phone and install it. Enable installation for the browser or file manager only if Android asks.

The app opens with a Connection screen. Run the Spring Boot/MySQL backend using the README, then enter its reachable address. No hosted production backend or sample business data is bundled. Owners must enter business records and an administrator must verify the parlour before customer booking is possible.

This is a debug-signed test app. It is not ready for commercial launch. Firebase Console access, live Firebase/SSLCommerz validation, original AI Studio design matching and physical-device testing remain outstanding. See docs/VERIFICATION.md.

Each CI runner may use a different debug signing key. An update may require uninstalling an older preview, which deletes device-bound guest history. Preserve any booking references before doing so.
