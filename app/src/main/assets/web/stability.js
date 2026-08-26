'use strict';

(() => {
  let lastGeometryRepairAt = 0;
  let repairAttempts = 0;
  let lastStableOrientation = 'unknown';
  let physicalDisplayWidth = 0;
  let physicalDisplayHeight = 0;
  let latencyTuningTimer = null;
  let geometryConsistencyTimer = null;
  let lastPacketsReceived = 0;
  let lastPacketsLost = 0;
  let lastEmitted = 0;
  let lastJitterDelay = 0;
  let currentAutoTier = 'balanced';
  let badQualityWindows = 0;
  let goodQualityWindows = 0;
  let lastTierChangeAt = 0;

  function physicalOrientation() {
    if (lastStableOrientation !== 'unknown') return lastStableOrientation;
    if (remoteGeometry?.orientation && remoteGeometry.orientation !== 'unknown') return remoteGeometry.orientation;
    const vw = Number(el.video.videoWidth || 0), vh = Number(el.video.videoHeight || 0);
    return vw && vh ? (vw > vh ? 'landscape' : 'portrait') : 'unknown';
  }

  function setExactAspectRatio() {
    const orientation = physicalOrientation();
    const w = physicalDisplayWidth || Number(remoteGeometry?.displayWidth || 0);
    const h = physicalDisplayHeight || Number(remoteGeometry?.displayHeight || 0);
    el.stage.classList.remove('aspect-20-9','aspect-19-5-9','aspect-16-9','aspect-4-3');
    if (orientation === 'landscape' && w > 1 && h > 1) {
      el.stage.style.aspectRatio = `${w} / ${h}`;
    } else {
      el.stage.style.removeProperty('aspect-ratio');
    }
  }

  function applyViewerGeometry() {
    // Viewer policy is absolute: the entire decoded frame must always remain visible.
    // Geometry mismatches are repaired on Android; the browser never hides them with crop/zoom.
    enforceAutoFit();
    const orientation = physicalOrientation();
    const landscape = orientation === 'landscape';
    const portrait = orientation === 'portrait';

    el.stage.classList.toggle('remote-landscape', landscape);
    el.stage.classList.toggle('remote-portrait', portrait);
    el.stage.classList.remove('frame-letterbox-fix');
    document.documentElement.classList.toggle('remote-landscape', landscape);
    document.documentElement.classList.toggle('remote-portrait', portrait);
    document.documentElement.classList.remove('frame-letterbox-fix');
    setExactAspectRatio();
  }

  const originalUpdateViewerGeometry = updateViewerGeometry;
  updateViewerGeometry = function(force = false) {
    originalUpdateViewerGeometry(force);
    applyViewerGeometry();
  };

  function applyReceiverTarget(targetMs) {
    if (!pc) return;
    const target = Math.max(35, Math.min(120, Math.round(targetMs)));
    for (const receiver of pc.getReceivers()) {
      if (receiver?.track?.kind !== 'video') continue;
      try {
        if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = target;
      } catch {}
      try {
        if ('playoutDelayHint' in receiver) receiver.playoutDelayHint = target / 1000;
      } catch {}
    }
  }

  tuneReceiverForLowLatency = function(receiver) {
    if (!receiver) return receiver;
    try {
      if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = 60;
    } catch {}
    try {
      if ('playoutDelayHint' in receiver) receiver.playoutDelayHint = 0.06;
    } catch {}
    return receiver;
  };

  const originalApplyRemoteGeometry = applyRemoteGeometry;
  applyRemoteGeometry = function(data) {
    const sourceW = Number(data?.displayWidth || 0);
    const sourceH = Number(data?.displayHeight || 0);
    if (sourceW > 1 && sourceH > 1) {
      physicalDisplayWidth = sourceW;
      physicalDisplayHeight = sourceH;
    }
    originalApplyRemoteGeometry(data);
    const expected = data?.orientation || (sourceW && sourceH ? (sourceW > sourceH ? 'landscape' : 'portrait') : 'unknown');
    if (expected !== 'unknown') lastStableOrientation = expected;
    if (data?.autoTier) {
      currentAutoTier = data.autoTier;
      window.RemoteLinkQuality?.updateAutoTier?.(currentAutoTier);
    }
    applyViewerGeometry();
    scheduleOrientationConsistencyCheck();
  };

  function scheduleOrientationConsistencyCheck() {
    setTimeout(checkOrientationConsistency, 150);
    setTimeout(checkOrientationConsistency, 700);
    setTimeout(checkOrientationConsistency, 1300);
  }

  function sendSessionCommand(payload) {
    if (!channelAuthenticated || !control || control.readyState !== 'open') return false;
    try {
      control.send(JSON.stringify({...payload, seq:++commandSeq}));
      return true;
    } catch {
      return false;
    }
  }

  function checkOrientationConsistency() {
    if (!channelAuthenticated || !control || control.readyState !== 'open') return;
    const vw = Number(el.video.videoWidth || 0);
    const vh = Number(el.video.videoHeight || 0);
    if (!vw || !vh || lastStableOrientation === 'unknown') return;

    applyViewerGeometry();
    const actual = vw > vh ? 'landscape' : 'portrait';
    if (actual === lastStableOrientation) {
      repairAttempts = 0;
      return;
    }

    const now = Date.now();
    if (now - lastGeometryRepairAt < 1000 || repairAttempts >= 3) return;
    lastGeometryRepairAt = now;
    repairAttempts += 1;
    sendSessionCommand({ type:'capture_geometry_refresh' });
  }

  function autoEnabled() {
    return (window.RemoteLinkQuality?.mode?.() || 'auto') === 'auto';
  }

  function requestAutoTier(tier, reason) {
    if (!autoEnabled()) return;
    if (!channelAuthenticated || !control || control.readyState !== 'open') return;
    if (tier === currentAutoTier) return;
    const now = Date.now();
    if (now - lastTierChangeAt < 4500) return;
    if (!sendSessionCommand({type:'capture_auto_tier', tier})) return;
    lastTierChangeAt = now;
    currentAutoTier = tier;
    window.RemoteLinkQuality?.updateAutoTier?.(tier);
    if (reason) setMessage(reason, tier === 'fluid' ? 'success' : '');
  }

  const originalBindControlChannel = bindControlChannel;
  bindControlChannel = function(channel) {
    originalBindControlChannel(channel);
    const previousMessage = channel.onmessage;
    channel.onmessage = ev => {
      let data = null;
      try { data = JSON.parse(ev.data); } catch {}
      previousMessage?.call(channel, ev);

      if (data?.type === 'auth_ok') {
        currentAutoTier = 'balanced';
        badQualityWindows = 0;
        goodQualityWindows = 0;
        lastPacketsReceived = 0;
        lastPacketsLost = 0;
        lastEmitted = 0;
        lastJitterDelay = 0;
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            applyReceiverTarget(60);
            applyViewerGeometry();
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'capture_auto_tier_result' && data.ok) {
        currentAutoTier = data.autoTier || currentAutoTier;
        window.RemoteLinkQuality?.updateAutoTier?.(currentAutoTier);
      }

      if (data?.type === 'display_geometry') {
        if (data.autoTier) currentAutoTier = data.autoTier;
        applyViewerGeometry();
        scheduleOrientationConsistencyCheck();
      }
    };
  };

  async function retuneLatencyFromStats() {
    if (!pc) return;
    try {
      const stats = await pc.getStats();
      let inbound = null;
      stats.forEach(report => {
        if (report.type === 'inbound-rtp' && report.kind === 'video') inbound = report;
      });
      if (!inbound) return;

      const jitterMs = Math.max(0, Number(inbound.jitter || 0) * 1000);
      const received = Number(inbound.packetsReceived || 0);
      const lost = Math.max(0, Number(inbound.packetsLost || 0));
      const emitted = Number(inbound.jitterBufferEmittedCount || 0);
      const jitterDelay = Number(inbound.jitterBufferDelay || 0);

      const deltaReceived = lastPacketsReceived > 0 ? Math.max(0, received - lastPacketsReceived) : 0;
      const deltaLost = lastPacketsLost > 0 ? Math.max(0, lost - lastPacketsLost) : 0;
      const deltaEmitted = lastEmitted > 0 ? Math.max(0, emitted - lastEmitted) : 0;
      const deltaJitterDelay = lastJitterDelay > 0 ? Math.max(0, jitterDelay - lastJitterDelay) : 0;
      lastPacketsReceived = received;
      lastPacketsLost = lost;
      lastEmitted = emitted;
      lastJitterDelay = jitterDelay;

      const packetWindow = deltaReceived + deltaLost;
      const lossPct = packetWindow > 0 ? (deltaLost / packetWindow) * 100 : 0;
      const bufferNowMs = deltaEmitted > 0 ? (deltaJitterDelay / deltaEmitted) * 1000 : 0;
      window.RemoteLinkQuality?.updateNetworkStats?.({jitterMs, lossPct, bufferMs:bufferNowMs});

      let targetMs = 42;
      if (jitterMs > 25 || lossPct > 1.0) targetMs = 115;
      else if (jitterMs > 12 || lossPct > 0.3) targetMs = 82;
      else if (jitterMs > 7 || lossPct > 0.1) targetMs = 60;
      applyReceiverTarget(targetMs);

      if (!autoEnabled()) {
        badQualityWindows = 0;
        goodQualityWindows = 0;
        return;
      }

      const severe = bufferNowMs > 185 || jitterMs > 32 || lossPct > 1.2;
      const pressured = bufferNowMs > 125 || jitterMs > 20 || lossPct > 0.55;
      const healthy = (bufferNowMs === 0 || bufferNowMs < 82) && jitterMs < 11 && lossPct < 0.25;

      if (severe) {
        badQualityWindows += 2;
        goodQualityWindows = 0;
      } else if (pressured) {
        badQualityWindows += 1;
        goodQualityWindows = 0;
      } else if (healthy) {
        goodQualityWindows += 1;
        badQualityWindows = Math.max(0, badQualityWindows - 1);
      } else {
        badQualityWindows = Math.max(0, badQualityWindows - 1);
        goodQualityWindows = 0;
      }

      if (currentAutoTier === 'fluid' && badQualityWindows >= 2) {
        badQualityWindows = 0;
        requestAutoTier('balanced', 'A rede variou: reduzindo de 60 FPS para manter a resposta rápida.');
      } else if (currentAutoTier === 'balanced' && badQualityWindows >= 3) {
        badQualityWindows = 0;
        requestAutoTier('economy', 'Rede local pressionada: reduzindo vídeo temporariamente para cortar a fila de atraso.');
      } else if (currentAutoTier === 'economy' && goodQualityWindows >= 4) {
        goodQualityWindows = 0;
        requestAutoTier('balanced', 'Rede estabilizada: restaurando qualidade balanceada.');
      } else if (currentAutoTier === 'balanced' && goodQualityWindows >= 6) {
        goodQualityWindows = 0;
        requestAutoTier('fluid', 'Rede excelente: Auto ativou 60 FPS.');
      }
    } catch {}
  }

  const originalStartStats = startStats;
  startStats = function() {
    originalStartStats();
    clearInterval(latencyTuningTimer);
    clearInterval(geometryConsistencyTimer);
    latencyTuningTimer = setInterval(retuneLatencyFromStats, 1500);
    retuneLatencyFromStats();

    geometryConsistencyTimer = setInterval(() => {
      if (!pc) return;
      checkOrientationConsistency();
      applyViewerGeometry();
    }, 800);
  };

  const originalClosePeerOnly = closePeerOnly;
  closePeerOnly = function(...args) {
    clearInterval(latencyTuningTimer);
    clearInterval(geometryConsistencyTimer);
    latencyTuningTimer = null;
    geometryConsistencyTimer = null;
    return originalClosePeerOnly(...args);
  };

  window.addEventListener('resize', applyViewerGeometry);
  document.addEventListener('fullscreenchange', () => requestAnimationFrame(applyViewerGeometry));
})();
