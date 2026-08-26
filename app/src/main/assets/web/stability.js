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
  let lastFramesDecoded = 0;
  let lastFramesDropped = 0;
  let lastTotalDecodeTime = 0;
  let lastNackCount = 0;
  let lastPliCount = 0;

  let receiverTargetMs = 80;
  let currentAutoTier = 'balanced';
  let badQualityWindows = 0;
  let goodQualityWindows = 0;
  let lastTierChangeAt = 0;

  const AUTO_TIER_RANK = { economy:0, balanced:1, fluid:2 };
  const AUTO_PROFILE_FOR_TIER = { economy:'economy', balanced:'balanced', fluid:'fluid' };

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
    // The complete decoded frame must always stay visible. Geometry mismatch is repaired at the
    // Android capture surface; the browser never masks it with crop/zoom.
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
    const target = Math.max(55, Math.min(160, Math.round(targetMs)));
    // Avoid continuously nudging the playout queue for insignificant metric noise.
    if (Math.abs(target - receiverTargetMs) < 8) return;
    receiverTargetMs = target;
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
    // 80 ms is still low latency on a LAN, but gives Chrome/Chromebook enough room to present
    // complete frames during a fast scroll instead of starving the decoder/compositor.
    try {
      if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = 80;
    } catch {}
    try {
      if ('playoutDelayHint' in receiver) receiver.playoutDelayHint = 0.08;
    } catch {}
    receiverTargetMs = 80;
    return receiver;
  };

  function syncAutoTierFromServer(data) {
    if (!autoEnabled() || !data) return;
    const profile = String(data.profile || '');
    if (profile in AUTO_TIER_RANK) {
      currentAutoTier = profile;
      window.RemoteLinkQuality?.updateAutoTier?.(currentAutoTier);
      return;
    }
    if (profile === 'auto' && data.autoTier in AUTO_TIER_RANK) {
      currentAutoTier = data.autoTier;
      window.RemoteLinkQuality?.updateAutoTier?.(currentAutoTier);
    }
  }

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
    syncAutoTierFromServer(data);
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

  function requestAutoTier(tier, reason, urgent = false) {
    if (!autoEnabled()) return false;
    if (!channelAuthenticated || !control || control.readyState !== 'open') return false;
    if (!(tier in AUTO_TIER_RANK) || tier === currentAutoTier) return false;

    const now = Date.now();
    const downshift = AUTO_TIER_RANK[tier] < AUTO_TIER_RANK[currentAutoTier];
    const minimumInterval = downshift ? (urgent ? 1400 : 2600) : 8000;
    if (now - lastTierChangeAt < minimumInterval) return false;

    // Auto intentionally drives the existing fixed profiles from the trusted browser logic.
    // The high Auto tier is the 45 FPS `fluid` profile; 60 FPS remains an explicit manual mode.
    const profile = AUTO_PROFILE_FOR_TIER[tier];
    if (!sendSessionCommand({type:'capture_profile', profile})) return false;

    lastTierChangeAt = now;
    currentAutoTier = tier;
    window.RemoteLinkQuality?.updateAutoTier?.(tier);
    if (reason) setMessage(reason, tier === 'fluid' ? 'success' : '');
    return true;
  }

  function resetQualityCounters() {
    badQualityWindows = 0;
    goodQualityWindows = 0;
    lastPacketsReceived = 0;
    lastPacketsLost = 0;
    lastEmitted = 0;
    lastJitterDelay = 0;
    lastFramesDecoded = 0;
    lastFramesDropped = 0;
    lastTotalDecodeTime = 0;
    lastNackCount = 0;
    lastPliCount = 0;
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
        resetQualityCounters();
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            receiverTargetMs = 0;
            applyReceiverTarget(80);
            applyViewerGeometry();
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'capture_profile_result' && data.ok) {
        syncAutoTierFromServer(data);
      }
      if (data?.type === 'capture_auto_tier_result' && data.ok) {
        syncAutoTierFromServer(data);
      }
      if (data?.type === 'display_geometry') {
        syncAutoTierFromServer(data);
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
        if (report.type === 'inbound-rtp' && (report.kind === 'video' || report.mediaType === 'video')) inbound = report;
      });
      if (!inbound) return;

      const jitterMs = Math.max(0, Number(inbound.jitter || 0) * 1000);
      const received = Number(inbound.packetsReceived || 0);
      const lost = Math.max(0, Number(inbound.packetsLost || 0));
      const emitted = Number(inbound.jitterBufferEmittedCount || 0);
      const jitterDelay = Number(inbound.jitterBufferDelay || 0);
      const framesDecoded = Number(inbound.framesDecoded || 0);
      const framesDropped = Number(inbound.framesDropped || 0);
      const totalDecodeTime = Number(inbound.totalDecodeTime || 0);
      const nackCount = Number(inbound.nackCount || 0);
      const pliCount = Number(inbound.pliCount || 0);
      const fpsReal = Math.max(0, Number(inbound.framesPerSecond || 0));

      const deltaReceived = lastPacketsReceived > 0 ? Math.max(0, received - lastPacketsReceived) : 0;
      const deltaLost = lastPacketsLost > 0 ? Math.max(0, lost - lastPacketsLost) : 0;
      const deltaEmitted = lastEmitted > 0 ? Math.max(0, emitted - lastEmitted) : 0;
      const deltaJitterDelay = lastJitterDelay > 0 ? Math.max(0, jitterDelay - lastJitterDelay) : 0;
      const deltaDecoded = lastFramesDecoded > 0 ? Math.max(0, framesDecoded - lastFramesDecoded) : 0;
      const deltaDropped = lastFramesDropped > 0 ? Math.max(0, framesDropped - lastFramesDropped) : 0;
      const deltaDecodeTime = lastTotalDecodeTime > 0 ? Math.max(0, totalDecodeTime - lastTotalDecodeTime) : 0;
      const deltaNack = lastNackCount > 0 ? Math.max(0, nackCount - lastNackCount) : 0;
      const deltaPli = lastPliCount > 0 ? Math.max(0, pliCount - lastPliCount) : 0;

      lastPacketsReceived = received;
      lastPacketsLost = lost;
      lastEmitted = emitted;
      lastJitterDelay = jitterDelay;
      lastFramesDecoded = framesDecoded;
      lastFramesDropped = framesDropped;
      lastTotalDecodeTime = totalDecodeTime;
      lastNackCount = nackCount;
      lastPliCount = pliCount;

      const packetWindow = deltaReceived + deltaLost;
      const lossPct = packetWindow > 0 ? (deltaLost / packetWindow) * 100 : 0;
      const bufferNowMs = deltaEmitted > 0 ? (deltaJitterDelay / deltaEmitted) * 1000 : 0;
      const frameWindow = deltaDecoded + deltaDropped;
      const dropPct = frameWindow > 0 ? (deltaDropped / frameWindow) * 100 : 0;
      const decodeMs = deltaDecoded > 0 ? (deltaDecodeTime / deltaDecoded) * 1000 : 0;
      const frameBudgetMs = fpsReal > 1 ? 1000 / fpsReal : 33.3;
      const decodePressured = decodeMs > Math.min(18, frameBudgetMs * 0.72);
      const decodeSevere = decodeMs > Math.min(24, frameBudgetMs * 0.92);
      const feedbackBurst = deltaPli >= 1 || deltaNack >= 4;
      const feedbackSevere = deltaPli >= 2 || deltaNack >= 10;

      window.RemoteLinkQuality?.updateNetworkStats?.({
        jitterMs,
        lossPct,
        bufferMs:bufferNowMs,
        decodeMs,
        dropPct,
        fps:fpsReal,
        nack:deltaNack,
        pli:deltaPli,
        targetMs:receiverTargetMs
      });

      // Keep low latency, but stop starving the decoder/compositor. Decoder pressure also raises
      // the safety margin because a near-empty queue makes fast-motion artifacts more visible.
      let targetMs = 65;
      if (jitterMs > 25 || lossPct > 1.0 || dropPct > 2.0 || decodeSevere || feedbackSevere) targetMs = 140;
      else if (jitterMs > 12 || lossPct > 0.3 || dropPct > 0.7 || decodePressured || feedbackBurst) targetMs = 105;
      else if (jitterMs > 7 || lossPct > 0.1 || dropPct > 0.2) targetMs = 80;
      applyReceiverTarget(targetMs);

      if (!autoEnabled()) {
        badQualityWindows = 0;
        goodQualityWindows = 0;
        return;
      }

      const severe = bufferNowMs > 210 || jitterMs > 30 || lossPct > 1.2 ||
        dropPct > 2.5 || decodeSevere || feedbackSevere;
      const pressured = bufferNowMs > 150 || jitterMs > 18 || lossPct > 0.5 ||
        dropPct > 0.7 || decodePressured || feedbackBurst;
      const healthy = (bufferNowMs === 0 || bufferNowMs < 100) && jitterMs < 9 && lossPct < 0.2 &&
        dropPct < 0.25 && !decodePressured && deltaPli === 0 && deltaNack < 2;

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

      // Downshift quickly on decoder/render pressure; recover deliberately so quality changes do
      // not oscillate while the user is scrolling. Auto's top tier is 45 FPS, never forced 60.
      if (currentAutoTier === 'fluid' && severe) {
        badQualityWindows = 0;
        requestAutoTier('balanced', 'Frames instáveis: Auto voltou para 30 FPS para eliminar artefatos.', true);
      } else if (currentAutoTier === 'fluid' && badQualityWindows >= 2) {
        badQualityWindows = 0;
        requestAutoTier('balanced', 'A transmissão variou: reduzindo FPS para manter a imagem íntegra.', true);
      } else if (currentAutoTier === 'balanced' && badQualityWindows >= 3) {
        badQualityWindows = 0;
        requestAutoTier('economy', 'Pressão detectada no vídeo: reduzindo resolução para estabilizar os frames.', true);
      } else if (currentAutoTier === 'economy' && goodQualityWindows >= 6) {
        goodQualityWindows = 0;
        requestAutoTier('balanced', 'Transmissão estabilizada: restaurando a qualidade balanceada.');
      } else if (currentAutoTier === 'balanced' && goodQualityWindows >= 12) {
        goodQualityWindows = 0;
        requestAutoTier('fluid', 'Conexão e decoder estáveis: Auto ativou o modo fluido de 45 FPS.');
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
    resetQualityCounters();
    return originalClosePeerOnly(...args);
  };

  window.addEventListener('resize', applyViewerGeometry);
  document.addEventListener('fullscreenchange', () => requestAnimationFrame(applyViewerGeometry));
})();
