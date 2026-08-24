'use strict';

const $ = s => document.querySelector(s);
const el = {
  badge:$('#serverBadge'), form:$('#pairForm'), code:$('#code'), pairButton:$('#pairButton'), message:$('#message'),
  captureDot:$('#captureDot'), captureLabel:$('#captureLabel'), controlDot:$('#controlDot'), controlLabel:$('#controlLabel'),
  stage:$('#screenStage'), video:$('#remoteVideo'), placeholder:$('#screenPlaceholder'), connecting:$('#connectingOverlay'), connectingDetail:$('#connectingDetail'),
  hint:$('#interactionHint'), title:$('#viewerTitle'), rtcState:$('#rtcState'), disconnectTop:$('#disconnectTop'), fullscreen:$('#fullscreenButton'), fit:$('#fitButton'),
  back:$('#backButton'), home:$('#homeButton'), recents:$('#recentsButton'), text:$('#textInput'), sendText:$('#sendTextButton'),
  latency:$('#latencyStat'), resolution:$('#resolutionStat'), fps:$('#fpsStat'), bitrate:$('#bitrateStat')
};

let token=sessionStorage.getItem('remotelink_session')||'';
let pc=null, control=null, controlReady=false, channelAuthenticated=false, commandSeq=0;
let remoteCandidateTimer=null, statsTimer=null, stateTimer=null;
let browserCandidates=[], answerInstalled=false, pointerStart=null, lastBytes=null, lastBytesAt=null, fitMode='contain';
let connectGeneration=0;
const pings=new Map();

function setMessage(text,kind=''){el.message.textContent=text;el.message.className=`message ${kind}`;}
function setConnectOverlay(show,detail='Negociando conexão direta na rede local…'){el.connecting.classList.toggle('hidden',!show);el.connectingDetail.textContent=detail;}
function authHeaders(){return {'Authorization':`Bearer ${token}`};}
function jsonHeaders(){return {...authHeaders(),'Content-Type':'application/json'};}
function sleep(ms){return new Promise(r=>setTimeout(r,ms));}
function prettyState(s){return ({new:'iniciando',connecting:'conectando',connected:'conectado',disconnected:'instável',failed:'falhou',closed:'encerrado'})[s]||s;}

async function apiStatus(){
  try{
    const r=await fetch('/api/status',{cache:'no-store'});if(!r.ok)throw new Error('offline');
    const s=await r.json();
    el.badge.className='status-pill ok';el.badge.innerHTML='<i></i> LAN ativa';
    updateReadiness(s.captureReady,s.accessibilityReady);return s;
  }catch{
    el.badge.className='status-pill neutral';el.badge.innerHTML='<i></i> sem conexão';updateReadiness(false,false);return null;
  }
}
function updateReadiness(captureReady,accessibilityReady){
  el.captureDot.className=`readiness-dot ${captureReady?'ok':'warn'}`;el.captureLabel.textContent=captureReady?'autorizada':'ative no Android';
  el.controlDot.className=`readiness-dot ${accessibilityReady?'ok':'warn'}`;el.controlLabel.textContent=accessibilityReady?'autorizado':'ative acessibilidade';
}

async function waitForCaptureReady(timeoutMs=90000){
  const deadline=Date.now()+timeoutMs;
  while(token&&Date.now()<deadline){
    const s=await apiStatus();
    if(s?.captureReady)return s;
    setConnectOverlay(true,'Pareamento aprovado. No celular, toque em “Autorizar transmissão de tela” e confirme a captura.');
    setMessage('Sessão aprovada. Aguardando a autorização de transmissão no celular…','success');
    await sleep(600);
  }
  throw new Error('A sessão foi aprovada, mas a transmissão de tela não foi autorizada a tempo. Você pode tentar novamente sem gerar outro código.');
}

el.code.addEventListener('input',()=>{const d=el.code.value.replace(/\D/g,'').slice(0,6);el.code.value=d.length>3?`${d.slice(0,3)} ${d.slice(3)}`:d;});
el.form.addEventListener('submit',async ev=>{
  ev.preventDefault();

  // If Android already approved this browser, reuse that high-entropy session.
  // A transient MediaProjection/WebRTC race must never force a second pairing code.
  if(token){
    el.pairButton.disabled=true;el.pairButton.textContent='Conectando…';
    try{await startWebRtc();setMessage('Sessão reconectada.','success');}
    catch(e){setMessage(e.message||'Falha ao reconectar.','error');el.pairButton.disabled=false;el.pairButton.textContent='Tentar novamente';}
    return;
  }

  const code=el.code.value.replace(/\D/g,'');if(code.length!==6)return setMessage('Digite os 6 dígitos.','error');
  el.pairButton.disabled=true;setMessage('Validando código…');
  try{
    const r=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:`code=${encodeURIComponent(code)}`});
    const data=await safeJson(r);
    if(!r.ok){const labels={invalid_code:'Código incorreto.',expired:'Código expirado. Gere outro no celular.',pairing_locked:'Muitas tentativas. Gere um novo código.',pairing_closed:'O pareamento está fechado.'};throw new Error(labels[data.error]||'Não foi possível parear.');}
    setMessage('Código correto. Aprove a solicitação no celular.');
    token=await pollPair(data.requestId);sessionStorage.setItem('remotelink_session',token);
    setMessage('Aprovado. Preparando a transmissão…','success');
    await startWebRtc();
  }catch(e){
    setMessage(e.message||'Falha de conexão.','error');el.pairButton.disabled=false;
    el.pairButton.textContent=token?'Tentar novamente':'Conectar';
  }
});

async function pollPair(id){
  for(let i=0;i<90;i++){
    await sleep(800);const r=await fetch(`/api/pair/status?id=${encodeURIComponent(id)}`,{cache:'no-store'});const d=await safeJson(r);
    if(d.status==='approved')return d.token;
    if(d.status==='denied'||r.status===403)throw new Error('Solicitação recusada no celular.');
    if(!r.ok&&r.status!==202)throw new Error('O pareamento expirou.');
  }
  throw new Error('A solicitação expirou.');
}

function preferHardwareFriendlyVideoCodec(transceiver){
  try{
    if(!transceiver?.setCodecPreferences||!window.RTCRtpReceiver?.getCapabilities)return;
    const caps=RTCRtpReceiver.getCapabilities('video');
    const codecs=caps?.codecs||[];
    const h264=codecs.filter(c=>String(c.mimeType).toLowerCase()==='video/h264');
    if(!h264.length)return;
    const rest=codecs.filter(c=>String(c.mimeType).toLowerCase()!=='video/h264');
    transceiver.setCodecPreferences([...h264,...rest]);
  }catch{}
}

async function startWebRtc(){
  if(!token)throw new Error('Sessão de pareamento ausente.');
  if(!('RTCPeerConnection'in window))throw new Error('Este navegador não oferece WebRTC.');

  const generation=++connectGeneration;
  await waitForCaptureReady();
  if(generation!==connectGeneration||!token)return;

  closePeerOnly(false);setConnectOverlay(true,'Criando conexão WebRTC…');el.title.textContent='Conectando ao Android';
  browserCandidates=[];answerInstalled=false;controlReady=false;channelAuthenticated=false;commandSeq=0;
  pc=new RTCPeerConnection({iceServers:[],iceTransportPolicy:'all',bundlePolicy:'max-bundle'});
  const videoTransceiver=pc.addTransceiver('video',{direction:'recvonly'});
  preferHardwareFriendlyVideoCodec(videoTransceiver);
  control=pc.createDataChannel('control',{ordered:true});bindControlChannel(control);
  pc.ontrack=ev=>{
    const stream=ev.streams?.[0]||new MediaStream([ev.track]);
    el.video.srcObject=stream;el.video.play().catch(()=>{});
    el.placeholder.classList.add('hidden');el.stage.classList.add('streaming');el.title.textContent='Android conectado';
  };
  el.video.onloadedmetadata=()=>{el.video.play().catch(()=>{});};
  pc.onconnectionstatechange=()=>updateConnectionState(pc?.connectionState||'closed');
  pc.oniceconnectionstatechange=()=>{if(pc?.iceConnectionState==='failed')setConnectOverlay(false);};
  pc.onicecandidate=ev=>{if(!ev.candidate)return;const c=ev.candidate.toJSON?ev.candidate.toJSON():ev.candidate;if(answerInstalled)postBrowserCandidate(c).catch(()=>{});else browserCandidates.push(c);};

  const offer=await pc.createOffer();await pc.setLocalDescription(offer);setConnectOverlay(true,'Aguardando resposta do celular…');
  const r=await fetch('/api/webrtc/offer',{method:'POST',headers:jsonHeaders(),body:JSON.stringify({sdp:offer.sdp})});const data=await safeJson(r);
  if(!r.ok){
    if(data.error==='capture_not_ready'){
      setConnectOverlay(true,'A captura ainda está iniciando no Android. Tentando novamente…');await sleep(650);
      return startWebRtc();
    }
    throw new Error(data.detail||'Falha ao criar a sessão WebRTC.');
  }
  await pc.setRemoteDescription({type:'answer',sdp:data.sdp});answerInstalled=true;
  await Promise.allSettled(browserCandidates.splice(0).map(postBrowserCandidate));startRemoteCandidatePolling();startStatePolling();startStats();
}

async function postBrowserCandidate(c){if(!token||!c?.candidate)return;await fetch('/api/webrtc/candidate',{method:'POST',headers:jsonHeaders(),body:JSON.stringify({candidate:c.candidate,sdpMid:c.sdpMid??null,sdpMLineIndex:c.sdpMLineIndex})});}
function startRemoteCandidatePolling(){
  clearInterval(remoteCandidateTimer);remoteCandidateTimer=setInterval(async()=>{
    if(!pc||!token)return;try{const r=await fetch('/api/webrtc/candidates',{headers:authHeaders(),cache:'no-store'});if(!r.ok)return;const d=await r.json();for(const c of d.candidates||[]){try{await pc.addIceCandidate({candidate:c.candidate,sdpMid:c.sdpMid,sdpMLineIndex:c.sdpMLineIndex});}catch{}}}catch{}
  },350);
}
function startStatePolling(){
  clearInterval(stateTimer);stateTimer=setInterval(async()=>{
    if(!token)return;try{const r=await fetch('/api/webrtc/state',{headers:authHeaders(),cache:'no-store'});if(!r.ok)return;const d=await r.json();updateReadiness(d.captureReady,d.accessibilityReady);if(channelAuthenticated&&control?.readyState==='open')setControlEnabled(!!d.accessibilityReady);}catch{}
  },1500);
}

function bindControlChannel(ch){
  ch.onopen=()=>ch.send(JSON.stringify({type:'auth',token}));
  ch.onclose=()=>{channelAuthenticated=false;setControlEnabled(false);};ch.onerror=ch.onclose;
  ch.onmessage=ev=>{
    let d;try{d=JSON.parse(ev.data);}catch{return;}
    if(d.type==='hello'&&ch.readyState==='open')ch.send(JSON.stringify({type:'auth',token}));
    if(d.type==='auth_ok'){channelAuthenticated=true;setControlEnabled(!!d.controlReady);setConnectOverlay(false);el.hint.classList.remove('hidden');setTimeout(()=>el.hint.classList.add('hidden'),3500);}
    if(d.type==='auth_failed'){channelAuthenticated=false;setMessage('A autenticação do canal de controle falhou.','error');setControlEnabled(false);}
    if(d.type==='pong'){const started=pings.get(d.id);if(started){el.latency.textContent=`${Math.max(1,Math.round(performance.now()-started))} ms`;pings.delete(d.id);}}
    if(d.type==='control_error'&&d.error==='accessibility_disabled'){updateReadiness(true,false);setControlEnabled(false);}
  };
}
function updateConnectionState(state){
  el.rtcState.textContent=prettyState(state);el.rtcState.classList.toggle('live',state==='connected');
  if(state==='connected'){
    setConnectOverlay(false);el.disconnectTop.classList.remove('hidden');el.fullscreen.disabled=false;el.fit.disabled=false;el.stage.classList.add('connected');
    el.pairButton.textContent='Conectado';el.pairButton.disabled=true;setMessage('Transmissão conectada.','success');
  }else if(state==='failed'||state==='closed'){
    setConnectOverlay(false);setControlEnabled(false);el.stage.classList.remove('connected');
  }
}
function setControlEnabled(enabled){controlReady=enabled;[el.back,el.home,el.recents,el.text,el.sendText].forEach(x=>x.disabled=!enabled);}
function sendControl(payload){if(!controlReady||!control||control.readyState!=='open')return false;control.send(JSON.stringify({...payload,seq:++commandSeq}));return true;}
el.back.addEventListener('click',()=>sendControl({type:'back'}));el.home.addEventListener('click',()=>sendControl({type:'home'}));el.recents.addEventListener('click',()=>sendControl({type:'recents'}));
el.sendText.addEventListener('click',sendText);el.text.addEventListener('keydown',ev=>{if(ev.key==='Enter'){ev.preventDefault();sendText();}});
function sendText(){if(!el.text.value)return;if(sendControl({type:'text',text:el.text.value}))setMessage('Texto enviado ao campo selecionado.','success');}

function normalizedPoint(clientX,clientY){
  const rect=el.video.getBoundingClientRect(),vw=el.video.videoWidth,vh=el.video.videoHeight;if(!vw||!vh||!rect.width||!rect.height)return null;
  const scale=fitMode==='contain'?Math.min(rect.width/vw,rect.height/vh):Math.max(rect.width/vw,rect.height/vh);
  const displayW=vw*scale,displayH=vh*scale,offX=(rect.width-displayW)/2,offY=(rect.height-displayH)/2;
  const x=(clientX-rect.left-offX)/displayW,y=(clientY-rect.top-offY)/displayH;if(x<0||x>1||y<0||y>1)return null;return{x,y};
}
el.stage.addEventListener('pointerdown',ev=>{if(!controlReady||!el.stage.classList.contains('streaming'))return;const p=normalizedPoint(ev.clientX,ev.clientY);if(!p)return;pointerStart={...p,px:ev.clientX,py:ev.clientY,at:performance.now(),id:ev.pointerId};try{el.stage.setPointerCapture(ev.pointerId);}catch{}ev.preventDefault();});
el.stage.addEventListener('pointerup',ev=>{
  if(!pointerStart||pointerStart.id!==ev.pointerId)return;const end=normalizedPoint(ev.clientX,ev.clientY),start=pointerStart;pointerStart=null;if(!end)return;
  const distPx=Math.hypot(ev.clientX-start.px,ev.clientY-start.py),heldMs=performance.now()-start.at;
  if(distPx<10&&heldMs<550)sendControl({type:'tap',x:end.x,y:end.y});
  else{
    // Remote gestures should follow the pointer quickly; replaying the full time
    // the user held the mouse made swipes feel delayed by hundreds of ms.
    const duration=Math.max(100,Math.min(360,120+distPx*.32));
    sendControl({type:'swipe',x1:start.x,y1:start.y,x2:end.x,y2:end.y,duration:Math.round(duration)});
  }
  ev.preventDefault();
});
el.stage.addEventListener('pointercancel',()=>{pointerStart=null;});el.stage.addEventListener('contextmenu',ev=>ev.preventDefault());
el.stage.addEventListener('wheel',ev=>{if(!controlReady||!el.stage.classList.contains('streaming'))return;const d=Math.sign(ev.deltaY);if(!d)return;if(d>0)sendControl({type:'swipe',x1:.5,y1:.68,x2:.5,y2:.32,duration:180});else sendControl({type:'swipe',x1:.5,y1:.32,x2:.5,y2:.68,duration:180});ev.preventDefault();},{passive:false});

el.fullscreen.addEventListener('click',async()=>{try{if(!document.fullscreenElement)await el.stage.requestFullscreen();else await document.exitFullscreen();}catch{}});
el.fit.addEventListener('click',()=>{fitMode=fitMode==='contain'?'cover':'contain';el.video.style.objectFit=fitMode;el.fit.textContent=fitMode==='contain'?'↕':'↔';el.fit.title=fitMode==='contain'?'Preencher tela':'Ajustar tela';});
el.disconnectTop.addEventListener('click',()=>disconnect(true));

function startStats(){
  clearInterval(statsTimer);lastBytes=null;lastBytesAt=null;statsTimer=setInterval(async()=>{
    if(!pc)return;try{
      const stats=await pc.getStats();let inbound=null;stats.forEach(r=>{if(r.type==='inbound-rtp'&&r.kind==='video')inbound=r;});
      if(inbound){const w=inbound.frameWidth||el.video.videoWidth,h=inbound.frameHeight||el.video.videoHeight;el.resolution.textContent=w&&h?`${w}×${h}`:'—';el.fps.textContent=inbound.framesPerSecond?`${Math.round(inbound.framesPerSecond)}`:'—';const now=performance.now();if(lastBytes!=null&&inbound.bytesReceived!=null){const seconds=(now-lastBytesAt)/1000,mbps=((inbound.bytesReceived-lastBytes)*8/seconds)/1_000_000;el.bitrate.textContent=`${Math.max(0,mbps).toFixed(1)} Mb/s`;}lastBytes=inbound.bytesReceived;lastBytesAt=now;}
      if(control?.readyState==='open'&&channelAuthenticated){const id=Date.now();pings.set(id,performance.now());control.send(JSON.stringify({type:'ping',id}));setTimeout(()=>pings.delete(id),10000);}
    }catch{}
  },1500);
}

async function disconnect(invalidateSession){
  connectGeneration++;
  const currentToken=token;if(invalidateSession&&currentToken){try{await fetch('/api/session/stop',{method:'POST',headers:{'Authorization':`Bearer ${currentToken}`},keepalive:true});}catch{}sessionStorage.removeItem('remotelink_session');token='';}
  closePeerOnly(false);el.placeholder.classList.remove('hidden');el.stage.classList.remove('streaming','connected');el.video.srcObject=null;el.title.textContent='Aguardando conexão';el.disconnectTop.classList.add('hidden');el.fullscreen.disabled=true;el.fit.disabled=true;el.rtcState.textContent='offline';el.rtcState.classList.remove('live');setControlEnabled(false);el.latency.textContent=el.resolution.textContent=el.fps.textContent=el.bitrate.textContent='—';
  if(invalidateSession){el.pairButton.textContent='Conectar';el.pairButton.disabled=false;el.code.value='';setMessage('Sessão encerrada. Gere um novo código no Android para iniciar outra sessão.');}
}
function closePeerOnly(invalidateGeneration=true){if(invalidateGeneration)connectGeneration++;clearInterval(remoteCandidateTimer);clearInterval(statsTimer);clearInterval(stateTimer);remoteCandidateTimer=statsTimer=stateTimer=null;try{control?.close();}catch{}control=null;try{pc?.close();}catch{}pc=null;browserCandidates=[];answerInstalled=false;controlReady=false;channelAuthenticated=false;commandSeq=0;pings.clear();}
async function safeJson(r){try{return await r.json();}catch{return{};}}
async function resumeIfPossible(){const s=await apiStatus();if(!s||!token)return;try{const r=await fetch('/api/webrtc/state',{headers:authHeaders(),cache:'no-store'});if(!r.ok)throw new Error();setMessage('Sessão anterior encontrada. Reconectando…');el.pairButton.disabled=true;await startWebRtc();}catch{sessionStorage.removeItem('remotelink_session');token='';el.pairButton.disabled=false;el.pairButton.textContent='Conectar';}}
window.addEventListener('pagehide',()=>{if(token){try{fetch('/api/session/stop',{method:'POST',headers:authHeaders(),keepalive:true});}catch{}}closePeerOnly();});
resumeIfPossible();setInterval(apiStatus,5000);
