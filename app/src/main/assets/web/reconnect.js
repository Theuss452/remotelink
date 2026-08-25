'use strict';

(() => {
  const MAX_RETRIES = 3;
  const BASE_DELAY_MS = 1200;
  let retries = 0;
  let reconnectTimer = null;
  let reconnecting = false;
  let diagnosticTimer = null;
  let lastDiagnostic = null;
  let extraStatsTimer = null;
  let previousVideoStats = null;

  const originalUpdateConnectionState = updateConnectionState;
  const originalPostBrowserCandidate = postBrowserCandidate;
  const originalStartStats = startStats;

  postBrowserCandidate = async function(candidate) {
    if (!token || !candidate?.candidate) return;
    const response = await fetch('/api/webrtc/candidate', {
      method: 'POST', headers: jsonHeaders(),
      body: JSON.stringify({ candidate: candidate.candidate, sdpMid: candidate.sdpMid ?? null, sdpMLineIndex: candidate.sdpMLineIndex })
    });
    if (!response.ok) {
      const data = await safeJson(response);
      console.warn('RemoteLink rejeitou candidato ICE:', data.error || response.status);
    }
  };

  function diagnosticText(d) {
    if (!d) return 'Negociando WebRTC na rede local…';
    const localIce = pc?.iceConnectionState || 'new';
    const accepted = d.remoteCandidatesAccepted ?? 0;
    const rejected = d.remoteCandidatesRejected ?? 0;
    if (!d.captureReady) return 'A autorização de transmissão não está ativa no Android.';
    if (!d.captureStarted) return 'Preparando a captura de tela no Android…';
    if (localIce === 'failed') return 'ICE falhou. A rede pode estar bloqueando tráfego P2P entre os dispositivos.';
    if (localIce === 'disconnected') return 'Conexão ICE instável. Tentando recuperar…';
    if (accepted === 0 && rejected > 0) return `Candidatos ICE rejeitados: ${rejected}. Verificando compatibilidade da rede…`;
    if (accepted === 0) return 'Aguardando candidato de rede do Chromebook…';
    return `ICE negociando • ${accepted} candidato(s) aceito(s)${rejected ? ` • ${rejected} rejeitado(s)` : ''}`;
  }

  async function pollDiagnostics() {
    if (!token || !pc) return;
    try {
      const response = await fetch('/api/webrtc/state', { headers: authHeaders(), cache: 'no-store' });
      if (!response.ok) return;
      const data = await response.json();
      lastDiagnostic = data;
      if (pc.connectionState !== 'connected') setConnectOverlay(true, diagnosticText(data));
    } catch {}
  }

  function startDiagnostics() {
    clearInterval(diagnosticTimer);
    diagnosticTimer = setInterval(pollDiagnostics, 1000);
    pollDiagnostics();
  }

  async function retryWebRtc() {
    if (reconnecting || !token || retries >= MAX_RETRIES) return;
    reconnecting = true; retries += 1;
    const delay = BASE_DELAY_MS * retries;
    setMessage(`Conexão instável. Tentando reconectar (${retries}/${MAX_RETRIES})…`);
    setConnectOverlay(true, `Nova tentativa em ${(delay / 1000).toFixed(1)} s…`);
    await sleep(delay);
    if (!token) { reconnecting = false; return; }
    try { await startWebRtc(); startDiagnostics(); }
    catch (error) {
      reconnecting = false;
      if (retries < MAX_RETRIES && token) retryWebRtc();
      else {
        setConnectOverlay(false);
        const detail = lastDiagnostic ? diagnosticText(lastDiagnostic) : '';
        setMessage(`A reconexão automática falhou. ${detail}`.trim(), 'error');
        el.pairButton.disabled = false; el.pairButton.textContent = 'Reconectar';
      }
      return;
    }
    reconnecting = false;
  }

  updateConnectionState = function(state) {
    originalUpdateConnectionState(state);
    if (state === 'connected') {
      retries = 0; reconnecting = false; clearTimeout(reconnectTimer); reconnectTimer = null;
      clearInterval(diagnosticTimer); diagnosticTimer = null; return;
    }
    if ((state === 'failed' || state === 'disconnected') && token && !reconnecting) {
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(() => retryWebRtc(), state === 'disconnected' ? 1800 : 300);
    }
  };

  function delta(current, previous) {
    if (!Number.isFinite(current) || !Number.isFinite(previous)) return 0;
    return Math.max(0, current - previous);
  }

  startStats = function() {
    originalStartStats();
    const lossNode = ensureMetric('lossStat', 'Perda');
    const jitterNode = ensureMetric('jitterStat', 'Jitter');
    const bufferNode = ensureMetric('bufferStat', 'Buffer agora');
    const decodeNode = ensureMetric('decodeStat', 'Decode agora');
    const processNode = ensureMetric('processStat', 'Processo agora');
    const dropNode = ensureMetric('dropStat', 'Drops agora');

    previousVideoStats = null;
    clearInterval(extraStatsTimer);
    extraStatsTimer = setInterval(async () => {
      if (!pc) return;
      try {
        const stats = await pc.getStats();
        let inbound = null;
        stats.forEach(report => { if (report.type === 'inbound-rtp' && report.kind === 'video') inbound = report; });
        if (!inbound) return;

        const current = {
          packetsReceived: Number(inbound.packetsReceived || 0),
          packetsLost: Math.max(0, Number(inbound.packetsLost || 0)),
          emitted: Number(inbound.jitterBufferEmittedCount || 0),
          jitterDelay: Number(inbound.jitterBufferDelay || 0),
          decoded: Number(inbound.framesDecoded || 0),
          decodeTime: Number(inbound.totalDecodeTime || 0),
          processingDelay: Number(inbound.totalProcessingDelay || 0),
          dropped: Number(inbound.framesDropped || 0),
          receivedFrames: Number(inbound.framesReceived || 0)
        };

        jitterNode.textContent = Number.isFinite(inbound.jitter) ? `${Math.round(inbound.jitter * 1000)} ms` : '—';

        if (previousVideoStats) {
          const dReceived = delta(current.packetsReceived, previousVideoStats.packetsReceived);
          const dLost = delta(current.packetsLost, previousVideoStats.packetsLost);
          const packetWindow = dReceived + dLost;
          lossNode.textContent = packetWindow > 0 ? `${((dLost / packetWindow) * 100).toFixed(1)}%` : '0.0%';

          const dEmitted = delta(current.emitted, previousVideoStats.emitted);
          const dJitterDelay = delta(current.jitterDelay, previousVideoStats.jitterDelay);
          bufferNode.textContent = dEmitted > 0 ? `${Math.round((dJitterDelay / dEmitted) * 1000)} ms` : '—';

          const dDecoded = delta(current.decoded, previousVideoStats.decoded);
          const dDecodeTime = delta(current.decodeTime, previousVideoStats.decodeTime);
          decodeNode.textContent = dDecoded > 0 ? `${Math.round((dDecodeTime / dDecoded) * 1000)} ms` : '—';

          const dProcessing = delta(current.processingDelay, previousVideoStats.processingDelay);
          processNode.textContent = dDecoded > 0 ? `${Math.round((dProcessing / dDecoded) * 1000)} ms` : '—';

          const dDropped = delta(current.dropped, previousVideoStats.dropped);
          const dReceivedFrames = delta(current.receivedFrames, previousVideoStats.receivedFrames);
          dropNode.textContent = dReceivedFrames > 0 ? `${((dDropped / Math.max(1, dReceivedFrames)) * 100).toFixed(1)}%` : `${Math.round(dDropped)}`;
        } else {
          lossNode.textContent = '—';
          bufferNode.textContent = '—';
          decodeNode.textContent = '—';
          processNode.textContent = '—';
          dropNode.textContent = '—';
        }

        previousVideoStats = current;
      } catch {}
    }, 1200);
  };

  function ensureMetric(id, label) {
    let node = document.getElementById(id);
    if (node) {
      const labelNode = node.previousElementSibling;
      if (labelNode) labelNode.textContent = label;
      return node;
    }
    const grid = document.querySelector('.quick-stats');
    const item = document.createElement('div');
    item.innerHTML = `<span>${label}</span><strong id="${id}">—</strong>`;
    grid?.appendChild(item);
    return item.querySelector('strong');
  }

  window.addEventListener('online', () => {
    if (token && pc && pc.connectionState !== 'connected') retryWebRtc();
  });

  const originalStartWebRtc = startWebRtc;
  startWebRtc = async function() {
    previousVideoStats = null;
    const result = await originalStartWebRtc();
    startDiagnostics();
    return result;
  };
})();
