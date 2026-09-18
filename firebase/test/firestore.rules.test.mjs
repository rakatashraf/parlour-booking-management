import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc } from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-parlour-booking-management',
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') }
  });
  await env.withSecurityRulesDisabled(async context => {
    const db=context.firestore();
    await setDoc(doc(db,'parlours','10'),{parlour_id:10,owner_id:2,status:'VERIFIED'});
    await setDoc(doc(db,'parlours','11'),{parlour_id:11,owner_id:2,status:'PENDING'});
    await setDoc(doc(db,'parlours','12'),{parlour_id:12,owner_id:3,status:'PENDING'});
    await setDoc(doc(db,'staff','20'),{staff_id:20,parlour_id:11,status:'ACTIVE'});
    await setDoc(doc(db,'staff_schedules','30'),{schedule_id:30,staff_id:20});
    await setDoc(doc(db,'users','1'),{user_id:1,role:'CUSTOMER',status:'ACTIVE'});
    await setDoc(doc(db,'bookings','100'),{booking_id:100,customer_id:1,parlour_id:11,status:'CONFIRMED'});
    await setDoc(doc(db,'booking_items','101'),{booking_item_id:101,booking_id:100,service_id:40});
    await setDoc(doc(db,'payments','102'),{payment_id:102,booking_id:100,status:'PAID'});
    await setDoc(doc(db,'categories','1'),{category_id:1,name:'UNISEX',status:'ACTIVE'});
    await setDoc(doc(db,'categories','2'),{category_id:2,name:'HIDDEN',status:'INACTIVE'});
    await setDoc(doc(db,'admin_actions','1'),{action_id:1,admin_id:9,action:'VERIFY'});
    await setDoc(doc(db,'system','core'),{schema_version:1});
  });
});
after(async () => { await env.cleanup(); });

test('public can read verified parlours but not pending ones', async () => {
  const db=env.unauthenticatedContext().firestore();
  await assertSucceeds(getDoc(doc(db,'parlours','10')));
  await assertFails(getDoc(doc(db,'parlours','11')));
});

test('owner can read own pending business and staff schedule only', async () => {
  const db=env.authenticatedContext('2',{role:'OWNER'}).firestore();
  await assertSucceeds(getDoc(doc(db,'parlours','11')));
  await assertFails(getDoc(doc(db,'parlours','12')));
  await assertSucceeds(getDoc(doc(db,'staff_schedules','30')));
});

test('customer and owner can read authorized booking details', async () => {
  const customer=env.authenticatedContext('1',{role:'CUSTOMER'}).firestore();
  const stranger=env.authenticatedContext('4',{role:'CUSTOMER'}).firestore();
  const owner=env.authenticatedContext('2',{role:'OWNER'}).firestore();
  await assertSucceeds(getDoc(doc(customer,'bookings','100')));
  await assertSucceeds(getDoc(doc(customer,'booking_items','101')));
  await assertSucceeds(getDoc(doc(owner,'payments','102')));
  await assertFails(getDoc(doc(stranger,'bookings','100')));
});

test('admin can read protected operational documents', async () => {
  const db=env.authenticatedContext('9',{role:'ADMIN'}).firestore();
  await assertSucceeds(getDoc(doc(db,'users','1')));
  await assertSucceeds(getDoc(doc(db,'admin_actions','1')));
  await assertSucceeds(getDoc(doc(db,'system','core')));
  await assertSucceeds(getDoc(doc(db,'categories','2')));
});

test('clients cannot write Firestore directly', async () => {
  const customer=env.authenticatedContext('1',{role:'CUSTOMER'}).firestore();
  const owner=env.authenticatedContext('2',{role:'OWNER'}).firestore();
  const admin=env.authenticatedContext('9',{role:'ADMIN'}).firestore();
  await assertFails(setDoc(doc(customer,'bookings','999'),{customer_id:1,parlour_id:10}));
  await assertFails(setDoc(doc(owner,'services','999'),{parlour_id:11,name:'Injected'}));
  await assertFails(setDoc(doc(admin,'categories','999'),{status:'ACTIVE'}));
  assert.ok(true);
});
