'use strict';
const $ = id => document.getElementById(id);
const categories = {LIVING:'생활 불편',TRAFFIC:'교통·안전',ENVIRONMENT:'환경·위생',CERTIFICATE:'증명서 발급 체험'};
let token = localStorage.getItem('citizen-demo-token') || '';
let orders = [];
const money = n => Number(n).toLocaleString('ko-KR')+'원';
function tell(message,error=false){ $('notice').textContent=message; $('notice').classList.toggle('error',error); }
async function api(path,method='GET',body){
 const controller=new AbortController(); const timeout=setTimeout(()=>controller.abort(),15000);
 try {
  const response=await fetch(path,{method,signal:controller.signal,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});
  const text=await response.text(); let data; try{data=JSON.parse(text);}catch{data={message:'서버 응답을 확인할 수 없습니다.'};}
  if(!response.ok) throw new Error(data.message || '요청 처리에 실패했습니다. 잠시 후 다시 시도해 주세요.');
  return data;
 } catch(e){if(e.name==='AbortError') throw new Error('서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.'); throw e;}
 finally{clearTimeout(timeout);}
}
function account(member){
 $('member-form').hidden=!!member; $('member-ready').hidden=!member;
 $('user-name').textContent=member?member.name+' 님':'체험 계정 미설정';
 $('member-welcome').textContent=member?member.name+' 님, 반갑습니다.':'';
}
async function load(){if(!token){orders=[];render();return;} orders=await api('/api/orders');render();}
function el(tag,text,cls){const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;}
function render(){
 $('total').textContent=orders.length;const list=$('list');list.replaceChildren();
 const filtered=orders.filter(o=>($('status-filter').value==='ALL'||o.status===$('status-filter').value)&&o.title.toLowerCase().includes($('search').value.toLowerCase()));
 if(!filtered.length){const e=el('div',orders.length?'검색 결과가 없습니다.':'접수된 민원이 없습니다.','empty');e.append(el('small','민원을 신청하면 이곳에서 접수 내역을 확인할 수 있습니다.'));list.append(e);return;}
 for(const o of filtered){
  const row=el('div',undefined,'record');const body=el('div',undefined,'record-body');
  const title=el('button',o.title,'record-title');title.type='button';title.onclick=()=>detail(o.id).catch(e=>tell(e.message,true));
  body.append(title,el('div',`${categories[o.category]} · ${new Date(o.created_at).toLocaleString('ko-KR')} · ${o.id.slice(0,8)}`,'record-meta'));row.append(body);
  row.append(el('span',o.status==='PAYMENT_PENDING'?'결제 대기':'접수 완료','badge'+(o.status==='PAYMENT_PENDING'?' pending':'')));
  if(o.status==='PAYMENT_PENDING'){const b=el('button',money(o.fee)+' 모의 결제','button outline');b.type='button';b.onclick=()=>pay(o,b);row.append(b);}
  list.append(row);
 }
}
async function pay(o,button){
 if(!confirm(money(o.fee)+' 모의 결제를 진행할까요? 실제 금액은 청구되지 않습니다.')) return;
 button.disabled=true;
 try{await api('/api/payments/simulate','POST',{orderId:o.id});await load();tell('모의 결제가 완료되었습니다. 민원이 접수되었습니다.');}
 catch(e){tell(e.message,true);}finally{button.disabled=false;}
}
async function detail(id){
 const o=await api('/api/orders/'+encodeURIComponent(id));const body=$('detail-body');body.replaceChildren();
 body.append(el('p','접수번호 '+o.id,'detail-id'),el('h3',o.title),el('p',categories[o.category]+' · '+(o.status==='PAYMENT_PENDING'?'결제 대기':'접수 완료')),el('p',o.content,'detail-content'),el('p','수수료: '+money(o.fee)));
 if(o.fee>0&&o.status==='RECEIVED'){const receipt=await api('/api/payments/'+encodeURIComponent(id));body.append(el('p','모의 결제 영수증: '+receipt.id,'detail-id'),el('p','결제 금액: '+money(receipt.amount)));}
 $('detail').showModal();
}
$('member-form').addEventListener('submit',async event=>{event.preventDefault();const b=event.submitter;b.disabled=true;try{const member=await api('/api/members/demo','POST',{name:$('member-name').value.trim()});token=member.token;localStorage.setItem('citizen-demo-token',token);account(member);await load();tell('체험 계정이 준비되었습니다. 민원을 작성해 주세요.');}catch(e){tell(e.message,true);}finally{b.disabled=false;}});
$('switch-user').onclick=()=>{if(!confirm('새 체험 계정을 만들까요? 기존 계정의 조회 토큰이 이 브라우저에서 제거됩니다.'))return;token='';localStorage.removeItem('citizen-demo-token');account(null);orders=[];render();tell('새로운 체험 계정을 만들어 주세요.');};
$('order-form').addEventListener('submit',async event=>{event.preventDefault();if(!token){tell('체험 계정을 먼저 만들어 주세요.',true);$('member-name').focus();return;}const b=event.submitter;b.disabled=true;try{const o=await api('/api/orders','POST',{category:$('category').value,title:$('title').value.trim(),content:$('content').value.trim()});$('order-form').reset();fee();$('char-count').textContent='0 / 2,000';await load();tell(o.fee>0?'신청이 저장되었습니다. 아래 나의 민원에서 모의 결제를 완료해 주세요.':'민원이 접수되었습니다. 아래 나의 민원에서 확인하세요.');$('history').scrollIntoView({behavior:'smooth'});}catch(e){tell(e.message,true);}finally{b.disabled=false;}});
function fee(){$('fee').textContent=$('category').value==='CERTIFICATE'?'1,000원 (모의 결제)':'무료';}
$('category').onchange=fee;
$('content').oninput=()=>{$('char-count').textContent=$('content').value.length+' / 2,000';};
$('refresh').onclick=async()=>{try{await load();tell('신청 내역을 새로고침했습니다.');}catch(e){tell(e.message,true);}};
$('status-filter').onchange=render;$('search').oninput=render;
$('close-detail').onclick=()=>$('detail').close();
document.querySelectorAll('[data-category]').forEach(b=>{b.onclick=()=>{$('category').value=b.dataset.category;fee();$('apply').scrollIntoView({behavior:'smooth'});};});
(async()=>{if(token){try{account(await api('/api/members/me'));await load();tell('체험 계정으로 연결되었습니다. 민원을 신청하거나 기존 내역을 확인하세요.');}catch(e){tell(e.message+' 새 계정이 필요하면 아래에서 생성하세요.',true);account(null);}}})();
