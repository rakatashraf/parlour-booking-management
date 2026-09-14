# Parlour Booking & Management

Development preview for the supplied project proposal: native Android Java/XML with MVVM, Spring Boot REST API, MySQL, Firebase FCM/Storage and SSLCommerz adapters.

**Status: development preview, not a launched commercial product.** AI Studio source could not be accessed through the supplied link. The native UI is a functional baseline, not a verified reproduction of that design. Firebase Console and Google sign-in returned gateway errors during development. No live Firebase settings, hosting, merchant credentials or billing were configured. See [verification and gaps](docs/VERIFICATION.md).

## Run the backend and database

1. Install Docker Desktop with Compose.
2. Copy `.env.example` to `.env` and replace all password/secret placeholders. `JWT_SECRET` needs at least 32 random bytes. Set `BOOTSTRAP_ADMIN_EMAIL` and a strong `BOOTSTRAP_ADMIN_PASSWORD` of at least 12 characters for the initial administrator.
3. Run `docker compose up --build` from the repository root.
4. Open `http://localhost:8080/api/public/health` and the administrator panel at `http://localhost:8080/admin/`.
5. Remove bootstrap credentials from your environment after the first administrator is created.

Flyway creates the schema on first startup. MySQL is internal to the Compose network and persists in `mysql-data`. No sample parlours, services, staff or appointments are seeded. Business records are entered by users. The administrator is created only when explicit bootstrap values are provided.

For a Java-only backend launch, install JDK 17, Maven 3.9+, and MySQL 8.4. Create a database and restricted database user, set `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET` and any integration environment variables, then run:

```sh
mvn -f backend/pom.xml test
mvn -f backend/pom.xml spring-boot:run
```

## Open and run in Android Studio

1. Open the **android/** directory as the project.
2. Use Gradle JDK 17. Install Android SDK Platform 35 and Build Tools 34.0.0 in SDK Manager.
3. Copy **android/app/google-services.example.json** to **android/app/google-services.json**. The example contains the Android client configuration you supplied, for package `com.parlour.management`. Client configuration is included for reproducible builds; it does not grant server or console access. Private server credentials are never included.
4. This recovered snapshot does not include a verified Gradle wrapper. Install Gradle 8.9 and run `gradle wrapper --gradle-version 8.9` in `android/`, then sync the project in Android Studio.
5. Run the `app` configuration on an Android 8.0+ device or emulator with Google Play services.
6. On the Connection screen enter your API address. The emulator uses `http://10.0.2.2:8080` for a local backend. A physical phone needs a reachable server address, or your computer's LAN IP while both devices are on the same network for debug testing.
7. Release builds allow HTTPS only. Production requires an HTTPS reverse proxy or managed host. The client never connects directly to MySQL.

To create an installation package, run `gradle assembleDebug` in `android/`, or use `./gradlew assembleDebug` once the wrapper has been generated. On Windows use `gradlew.bat assembleDebug`. The output is **android/app/build/outputs/apk/debug/app-debug.apk**.

A debug APK is signed for testing, not for Play Store release. No production API endpoint is compiled into this preview. Set one inside the app. Online bookings require your backend to be running.

## First business setup

1. In Android, Account → Register as an owner. Register the parlour profile.
2. Add services with durations and prices, staff, staff-to-service qualifications, business hours and staff schedules. Days use ISO numbering: Monday 1 through Sunday 7.
3. Set the booking/cancellation policy. Use pay-at-parlour while the gateway is unconfigured. Add closures and blocked periods as needed.
4. In the web administrator panel verify the parlour.
5. Switch Android to customer mode. Browse or search, choose services, staff and an available slot. Confirm using your own customer details.
6. Owners can accept pending requests, record cash received, complete appointments after their end time and approve cancellation requests. Customers can then rate parlours and staff separately.

Guest access is an automatically issued bearer session, not a customer sign-up form. Its token is encrypted with Android Keystore and expires after 90 days. Guest history is device-bound; clearing app data loses access. Owner sessions expire after one day. Owner accounts are separate from the Google account used to administer Firebase.

## Integration and build instructions

See [integration setup](docs/INTEGRATIONS.md), [database design](docs/SCHEMA.md), and [verification](docs/VERIFICATION.md).

GitHub Actions runs backend tests and builds Android. A repository secret named `GOOGLE_SERVICES_JSON` can override the supplied client configuration. Build artifacts are available on successful workflow runs. Never put service-account private keys, merchant passwords, JWT secrets, release keystores or production database passwords into git. Supply those through the hosting environment.
