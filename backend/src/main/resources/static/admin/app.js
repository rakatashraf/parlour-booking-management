'use strict';
const $=id=>document.getElementById(id);let token=sessionStorage.getItem('parlour-admin')||'',active='dashboard';
const view={page:0,sort:null,dir:1,filter:'',status:'',from:'',to:''};const PAGE=25;
function el(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=String(text??'');return n;}
async function api(path,body){const r=await fetch('/api'+path,{method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});let data;try{data=await r.json();}catch{throw Error('Server response could not be read');}if(!r.ok)throw Error(data.message||'Request failed ('+r.status+')');return data;}
function action(parent,label,fn){const b=el('button',label);b.onclick=async()=>{b.disabled=true;$('message').textContent='';try{await fn();}catch(e){$('message').textContent=e.message;}finally{b.disabled=false;}};parent.append(b);}
function fmt(v){if(v===null||v===undefined||v==='')return '—';if(typeof v==='number'&&v>946684800000){const d=new Date(v);if(!isNaN(d))return d.toLocaleString();}if(typeof v==='string'&&/^\d{4}-\d{2}-\d{2}/.test(v)){const d=new Date(v);if(!isNaN(d))return d.toLocaleString();}return String(v);}
$('loginForm').onsubmit=async e=>{e.preventDefault();try{const data=await api('/auth/login',Object.fromEntries(new FormData(e.target)));if(data.user.role!=='ADMIN')throw Error('Administrator account required');token=data.token;sessionStorage.setItem('parlour-admin',token);e.target.reset();await show('dashboard');}catch(e){$('message').textContent=e.message;}};
$('logout').onclick=()=>{sessionStorage.removeItem('parlour-admin');token='';location.reload();};
const tables=['dashboard','sync','parlours','users','bookings','payments','refund_requests','complaints','categories','banners','admin_actions'];for(const t of tables)action($('nav'),t.replaceAll('_',' '),()=>show(t));
async function show(table){active=table;Object.assign(view,{page:0,sort:null,dir:1,filter:'',status:'',from:'',to:''});$('login').hidden=true;$('workspace').hidden=false;$('title').textContent=table.replaceAll('_',' ');$('data').replaceChildren();$('actions').replaceChildren();action($('actions'),'Refresh',()=>show(table));if(table==='dashboard'){const data=await api('/admin/dashboard');const cards=el('div');cards.className='metrics';for(const[k,v]of Object.entries(data)){const card=el('div');card.className='metric';card.append(el('span',k.replaceAll('_',' ')),el('strong',v));cards.append(card);}$('data').append(cards);return;}if(table==='sync'){await renderSync();return;}if(table==='bookings'){await renderBookings();return;}const data=await api('/admin/'+table);if(['categories','banners'].includes(table))action($('actions'),'Add '+(table==='categories'?'category':'banner'),async()=>{const b={};for(const key of table==='categories'?['name']:['title','image_url']){const value=prompt(key.replaceAll('_',' '));if(!value)return;b[key]=value;}await api('/admin/'+table,b);await show(table);});if(!data.length){$('data').append(el('p','No records yet.'));return;}
action($('actions'),'Export CSV',()=>{const keys=Object.keys(data[0]);const cell=v=>'"'+String(v??'').replace(/^[=+@-]/,"'$&").replaceAll('"','""')+'"';const csv=[keys,...data.map(r=>keys.map(k=>r[k]))].map(r=>r.map(cell).join(',')).join('\r\n');const u=URL.createObjectURL(new Blob([csv],{type:'text/csv'}));const a=el('a');a.href=u;a.download=table+'.csv';a.click();URL.revokeObjectURL(u);});
const columns=Object.keys(data[0]).filter(k=>!['policy_snapshot','request_hash','idempotency_key'].includes(k));
const bar=el('div');bar.className='filters';const lab=el('label');lab.append('Filter ');const fi=el('input');fi.placeholder='type to filter rows…';fi.oninput=()=>{view.filter=fi.value;view.page=0;draw();};lab.append(fi);bar.append(lab);
const grid=el('table'),pager=el('div');pager.className='pager';
$('data').append(bar,grid,pager);
const draw=()=>drawTable(grid,pager,data,columns,actionsFor(table));draw();}
function actionsFor(table){return(controls,row)=>{
 if(table==='parlours')for(const status of['VERIFIED','SUSPENDED','REJECTED'])action(controls,status.toLowerCase(),async()=>{const reason=prompt('Reason');if(!reason)return;await api('/admin/parlours/'+row.parlour_id+'/verify',{status,reason});await show(table);});
 if(table==='users'&&row.role!=='ADMIN')action(controls,row.status==='ACTIVE'?'Suspend':'Activate',async()=>{const reason=prompt('Reason');if(!reason)return;await api('/admin/users/'+row.user_id+'/status',{status:row.status==='ACTIVE'?'SUSPENDED':'ACTIVE',reason});await show(table);});
 if(table==='complaints'&&row.status==='OPEN')action(controls,'Resolve',async()=>{const resolution=prompt('Resolution');if(!resolution)return;await api('/admin/complaints/'+row.complaint_id+'/resolve',{resolution});await show(table);});
 if(table==='refund_requests'){if(row.status==='PENDING')action(controls,'Submit refund',async()=>{if(!confirm('Submit BDT '+row.amount+' refund to the payment gateway?'))return;await api('/admin/refunds/'+row.refund_id+'/send',{});await show(table);});if(row.status!=='REFUNDED')action(controls,'Verify refund',async()=>{const gateway_reference=row.gateway_reference||prompt('Refund reference from the merchant portal');if(!gateway_reference)return;await api('/admin/refunds/'+row.refund_id+'/verify',{gateway_reference});await show(table);});}
};}
function drawTable(grid,pager,rows,columns,actionsFn){
 grid.replaceChildren();pager.replaceChildren();
 const head=el('tr');
 for(const key of[...columns,'actions']){
  const th=el('th',key==='actions'?'Actions':key.replaceAll('_',' ')+(view.sort===key?(view.dir===1?' ▲':' ▼'):''));
  if(key!=='actions'){th.classList.add('sortable');th.onclick=()=>{if(view.sort===key)view.dir*=-1;else{view.sort=key;view.dir=1;}drawTable(grid,pager,rows,columns,actionsFn);};}
  head.append(th);
 }
 grid.append(head);
 let body=rows;
 if(view.filter){const f=view.filter.toLowerCase();body=body.filter(r=>columns.some(k=>String(r[k]??'').toLowerCase().includes(f)));}
 if(view.sort){const k=view.sort,d=view.dir;body=[...body].sort((a,b)=>{let x=a[k]??'',y=b[k]??'';return(x<y?-1:x>y?1:0)*d;});}
 const pages=Math.max(1,Math.ceil(body.length/PAGE));if(view.page>pages-1)view.page=pages-1;
 for(const row of body.slice(view.page*PAGE,(view.page+1)*PAGE)){
  const tr=el('tr');
  for(const key of columns){const td=el('td');if(key==='status'&&typeof row[key]==='string'){const b=el('span',row[key]);b.className='badge st-'+row[key].toLowerCase();td.append(b);}else td.textContent=fmt(row[key]);tr.append(td);}
  const c=el('td');if(actionsFn)actionsFn(c,row);tr.append(c);grid.append(tr);
 }
 const prev=el('button','‹ Prev');prev.disabled=view.page===0;prev.onclick=()=>{view.page--;drawTable(grid,pager,rows,columns,actionsFn);};
 const next=el('button','Next ›');next.disabled=view.page>=pages-1;next.onclick=()=>{view.page++;drawTable(grid,pager,rows,columns,actionsFn);};
 pager.append(prev,next,el('span',body.length+' rows · page '+(view.page+1)+' of '+pages+' (server returns up to 300 rows; paging client-side)'));
}
async function renderBookings(){
 const data=await api('/admin/bookings');
 const bar=el('div');bar.className='filters';
 const mk=(t,n)=>{const l=el('label');l.append(t);l.append(n);bar.append(l);};
 const sel=el('select');sel.append(new Option('all statuses',''));for(const s of['PENDING_PAYMENT','PENDING_OWNER','CONFIRMED','REJECTED','COMPLETED','CANCEL_PENDING','CANCELLED'])sel.append(new Option(s,s));sel.onchange=()=>{view.status=sel.value;view.page=0;draw();};
 const from=el('input');from.type='date';from.onchange=()=>{view.from=from.value;view.page=0;draw();};
 const to=el('input');to.type='date';to.onchange=()=>{view.to=to.value;view.page=0;draw();};
 const q=el('input');q.placeholder='id, customer, phone…';q.oninput=()=>{view.filter=q.value;view.page=0;draw();};
 mk('Status ',sel);mk('From ',from);mk('To ',to);mk('Search ',q);
 const grid=el('table'),pager=el('div');pager.className='pager';const detailBox=el('div');
 $('data').append(bar,grid,pager,detailBox);
 const columns=['booking_id','start_at','status','customer_name','customer_phone','total_amount','advance_amount'];
 const rows=()=>{let out=data;
  if(view.status)out=out.filter(r=>r.status===view.status);
  if(view.from||view.to)out=out.filter(r=>{const d=String(r.booking_date||r.start_at||'').slice(0,10);return(!view.from||d>=view.from)&&(!view.to||d<=view.to);});
  if(view.filter){const f=view.filter.toLowerCase();out=out.filter(r=>['booking_id','customer_name','customer_phone'].some(k=>String(r[k]??'').toLowerCase().includes(f)));}
  if(view.sort){const k=view.sort,d=view.dir;out=[...out].sort((a,b)=>{let x=a[k]??'',y=b[k]??'';return(x<y?-1:x>y?1:0)*d;});}
  return out;};
 const draw=()=>drawTable(grid,pager,rows(),columns,(c,row)=>{
  action(c,'Details',async()=>{detailBox.replaceChildren();try{detailBox.append(bookingDetail(await api('/bookings/'+row.booking_id)));}catch(e){$('message').textContent=e.message;}});
  const st=row.status,bid=row.booking_id;
  if(st==='PENDING_OWNER'){
   action(c,'Confirm',async()=>{await api('/admin/bookings/'+bid+'/status',{status:'CONFIRMED'});await show('bookings');});
   action(c,'Reject',async()=>{if(!confirm('Reject booking #'+bid+'? Paid amounts are queued for refund.'))return;await api('/admin/bookings/'+bid+'/status',{status:'REJECTED'});await show('bookings');});
  }
  if(st==='CONFIRMED'){
   action(c,'Complete',async()=>{if(!confirm('Mark booking #'+bid+' complete?'))return;await api('/admin/bookings/'+bid+'/status',{status:'COMPLETED'});await show('bookings');});
   action(c,'Cancel',async()=>{if(!confirm('Cancel booking #'+bid+'? Policy refund rules apply.'))return;await api('/bookings/'+bid+'/cancel',{});await show('bookings');});
  }
  if(st==='PENDING_PAYMENT')action(c,'Cancel',async()=>{if(!confirm('Cancel booking #'+bid+'?'))return;await api('/bookings/'+bid+'/cancel',{});await show('bookings');});
  if(st==='CANCEL_PENDING')action(c,'Finalize cancellation',async()=>{if(!confirm('Finalize cancellation of booking #'+bid+'?'))return;await api('/admin/bookings/'+bid+'/status',{status:'CANCELLED'});await show('bookings');});
 });
 draw();
}
function bookingDetail(d){
 const box=el('div');box.className='detail';
 box.append(el('h3','Booking #'+d.booking_id+' · '+d.status));
 box.append(el('p',d.customer_name+' · '+d.customer_phone+' · '+fmt(d.start_at)+' → '+fmt(d.end_at)+' · total BDT '+d.total_amount+' · advance BDT '+(d.advance_amount??0)));
 if(d.items&&d.items.length){box.append(el('h4','Services'));const t=el('table'),h=el('tr');for(const c of['name','price','duration_min'])h.append(el('th',c.replaceAll('_',' ')));t.append(h);for(const i of d.items){const tr=el('tr');tr.append(el('td',i.name),el('td',i.price),el('td',i.duration_min));t.append(tr);}box.append(t);}
 if(d.payments&&d.payments.length){box.append(el('h4','Payments'));const t=el('table'),h=el('tr');const cols=['payment_id','transaction_id','amount','method','gateway','status','paid_at'];for(const c of cols)h.append(el('th',c.replaceAll('_',' ')));t.append(h);for(const p of d.payments){const tr=el('tr');for(const c of cols)tr.append(el('td',fmt(p[c])));t.append(tr);}box.append(t);}
 action(box,'Close',async()=>box.remove());
 return box;
}
async function renderSync(){
 const s=await api('/admin/firestore/status');
 if(!s.enabled){$('data').append(el('p','Firebase is not configured. Set FIREBASE_ENABLED=true and mount the service-account file at GOOGLE_APPLICATION_CREDENTIALS to enable Firestore synchronization.'));return;}
 const meta=s.metadata||{},cs=meta.collection_status||{};
 const cards=el('div');cards.className='metrics';
 const card=(k,v)=>{const c=el('div');c.className='metric';c.append(el('span',k),el('strong',fmt(v)));cards.append(c);};
 card('project',s.project);
 card('last full sync',fmt(meta.last_full_sync_at));
 card('last event sync',fmt(meta.last_event_sync_at));
 card('collections',(meta.collections||[]).length);
 const failed=Object.entries(cs).filter(([,v])=>v&&v.state==='FAILED');
 card('failed collections',failed.length);
 $('data').append(cards);
 if(failed.length)action($('actions'),'Retry failed',async()=>{if(!confirm('Retry '+failed.length+' failed collection(s)?'))return;const r=await api('/admin/firestore/sync/retry',{});$('message').textContent=r.ok?'Retry finished, all collections synced.':r.failed+' collection(s) still failing — see table below.';await show('sync');});
 action($('actions'),'Run full sync',async()=>{if(!confirm('Run a full Firestore resync of all collections? This can take a while.'))return;const r=await api('/admin/firestore/sync',{});$('message').textContent=r.ok?'Full sync finished: '+r.documents+' documents.':r.failed+' collection(s) failed — see table below.';await show('sync');});
 if(meta.last_event_sync_error){const w=el('p','Last event-sync error: '+meta.last_event_sync_error+' ('+fmt(meta.last_event_sync_failed_at)+')');w.style.color='#8b244d';$('data').append(w);}
 const grid=el('table'),head=el('tr');
 for(const h of['collection','status','documents','last sync','error'])head.append(el('th',h));
 grid.append(head);
 for(const name of(meta.collections||[])){
  const st=cs[name]||{},tr=el('tr');
  tr.append(el('td',name));
  const sc=el('td',st.state||'not synced yet');if(st.state==='FAILED')sc.style.color='#8b244d';tr.append(sc);
  tr.append(el('td',st.documents??'—'));
  tr.append(el('td',fmt(st.synced_at||st.failed_at)));
  const ec=el('td',st.error||'');if(st.error)ec.style.color='#8b244d';tr.append(ec);
  grid.append(tr);
 }
 $('data').append(grid);
}
if(token)show('dashboard').catch(e=>{$('message').textContent=e.message;});
