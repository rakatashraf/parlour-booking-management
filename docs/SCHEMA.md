# Database mapping

The migration `backend/src/main/resources/db/migration/V1__proposal_schema.sql` implements all 16 core tables from proposal section 3.5, preserving table and field names.

| Table | Purpose |
| --- | --- |
| users | Owner/admin authentication and automatic guest identities |
| parlours | Business profile, owner, location and verification |
| parlour_images | Ordered gallery and cover URLs |
| services | Price, duration and active status |
| staff | Designation, experience, expertise and rating |
| staff_services | Staff qualification for each service |
| staff_schedules | Weekly staff availability, ISO weekdays 1–7 |
| holidays | Full-day closures |
| blocked_slots | Whole-parlour or staff-specific blocks |
| bookings | Customer contact, staff, times, totals and lifecycle |
| booking_items | Booked service price and duration snapshots |
| payments | Unique gateway transaction and verified receipt |
| cancellation_policies | Advance, cancellation, refund and rescheduling rules |
| ratings | Completed-booking parlour and staff reviews |
| notifications | User inbox and FCM delivery outbox |
| admin_actions | Administrative audit records |

Supporting tables: business_hours, device_tokens, complaints, categories, banners and refund_requests. These are documented extensions, not replacements for proposal entities.

Booking extensions include customer_id, an idempotency key, a request hash, a 15-minute payment hold and a policy snapshot. Later policy edits do not change terms of an existing booking. Each parlour has a timezone. API appointment times are ISO 8601 instants; weekly opening hours and closure dates are local to the parlour.

No business rows are inserted by migrations. Services, staff, expertise, opening hours and staff schedules must be entered before availability appears. The administrator is bootstrapped only from explicitly supplied environment values.

Booking creation, cancellation and rescheduling serialize on the relevant parlour row. READ_COMMITTED transaction isolation ensures waiting requests see the preceding committed reservation. All selected services must be assigned to the same qualified staff member and run consecutively. Staff splitting and overnight appointments are not implemented.

Payments are separate from appointment status. A paid but expired or risky transaction does not reclaim a slot and creates a refund-review record. Repeated verified callbacks do not duplicate receipts. Refund records preserve the original payment amount.
