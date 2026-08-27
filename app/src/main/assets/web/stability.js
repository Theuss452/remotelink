'use strict';

(() => {
  let lastGeometryRepairAt = 0;
  let repairAttempts = 0;
  let lastStableOrientation = 'unknown';
  let latencyTuningTimer = null;
  let lastPacketsReceived = 0;
  let lastPacketsLost = 0;
  let lastEmitted = 0;
  let lastJitterDelay = 0;
  let lastFramesDropped = 0;
  let lastFramesDecoded = 0;
  let currentAdaptiveProfile = 'balanced';
  let badQualityWindows = 0;
  let goodQualityWindows = 0;
  let lastProfileChangeAt = 0;
  let frameCanvasMismatch = false;
  let orientationEpoch = 0;

  preferHardwareFriendlyVideoCodec = function() {};

  function physicalOrientation() {
    if (lastStableOrientation !== 'unknown') return lastStableOrientation;
    if (remoteGeometry?.orientation && remoteGeometry.orientation !== 'unknown') return remoteGeometry.orientation;
    const vw = Number(el.video.videoWidth || 0), vh = Number(el.video.videoHeight || 0);
    return vw && vh ? (vw > vh ? 'landscape' : 'portrait') : 'unknown';
  }

  function setAspectClass() {
    el.stage.classList.remove('aspect-20-9','aspect-19-5-9','aspect-16-9','aspect-4-3');
    if (physicalOrientation() !== 'landscape') return;
    const w = Number(remoteGeometry?.displayWidth || 0);
    const h = Number(remoteGeometry?.displayHeight || 0);
    const ratio = w > 0 && h > 0 ? Math.max(w,h) / Math.min(w,h) : 20 / 9;
    if (ratio >= 2.19) el.stage.classList.add('aspect-20-9');
    else if (ratio >= 2.05) el.stage.classList.add('aspect-19-5-9');
    else if (ratio >= 1.65) el.stage.classList.add('aspect-16-9');
    else el.stage.classList.add('aspect-4-3');
  }

  function clearTransientViewerState() {
    frameCanvasMismatch = false;
    el.stage.classList.remove('frame-letterbox-fix');
    document.documentElement.classList.remove('frame-letterbox-fix');
    el.stage.classList.remove('aspect-20-9','aspect-19-5-9','aspect-16-9','aspect-4-3');
  }

  function applyViewerGeometry() {
    enforceAutoFit();
    const orientation = physicalOrientation();
    const landscape = orientation === 'landscape';
    const portrait = orientation === 'portrait';
    const vw = Number(el.video.videoWidth || 0);
    const vh = Number(el.video.videoHeight || 0);

    frameCanvasMismatch = landscape && vw > 0 && vh > 0 && vw < vh;

    el.stage.classList.toggle('remote-landscape', landscape);
    el.stage.classList.toggle('remote-portrait', portrait);
    el.stage.classList.toggle('frame-letterbox-fix', frameCanvasMismatch);
    document.documentElement.classList.toggle('remote-landscape', landscape);
    document.documentElement.classList.toggle('remote-portrait', portrait);
    document.documentElement.classList.toggle('frame-letterbox-fix', frameCanvasMismatch);
    setAspectClass();
  }

  function refreshAfterOrientationChange(nextOrientation) {
    if (!nextOrientation || nextOrientation === 'unknown') return;
    const epoch = ++orientationEpoch;
    clearTransientViewerState();
    lastStableOrientation = nextOrientation;
    repairAttempts = 0;
    badQualityWindows = 0;
    goodQualityWindows = 0;
    applyViewerGeometry();

    // Reapply the encoder format after Android has settled into the new orientation.
    // This intentionally mirrors the manual quality/size change that used to fix the
    // portrait frame, but it does not resize MediaProjection/VirtualDisplay.
    const phases = [0, 90, 220, 480, 850, 1350];
    phases.forEach((delay, index) => setTimeout(() => {
      if (epoch !== orientationEpoch || !channelAuthenticated || !control || control.readyState !== 'open') return;
      clearTransientViewerState();
      applyViewerGeometry();
      try { updateViewerGeometry(true); } catch {}
      if (index === 1 || index === 3) sendControl({type:'capture_geometry_refresh'});
      if (index === 2 || index === 4) sendControl({type:'capture_profile', profile:currentAdaptiveProfile});
    }, delay));
  }

  const originalUpdateViewerGeometry = updateViewerGeometry;
  updateViewerGeometry = function(force = false) {
    originalUpdateViewerGeometry(force);
    applyViewerGeometry();
  };

  const originalNormalizedPoint = normalizedPoint;
  normalizedPoint = function(clientX, clientY) {
    if (!frameCanvasMismatch) return originalNormalizedPoint(clientX, clientY);
    const rect = el.stage.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    const x = (clientX - rect.left) / rect.width;
    const y = (clientY - rect.top) / rect.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    return {x, y};
  };

  function applyReceiverTarget(targetMs) {
    if (!pc) return;
    const target = Math.max(42, Math.min(105, Math.round(targetMs)));
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
    const sourceW = Number(data?.captureContentWidth || data?.displayWidth || 0);
    const sourceH = Number(data?.captureContentHeight || data?.displayHeight || 0);
    const expected = data?.orientation || (sourceW && sourceH ? (sourceW > sourceH ? 'landscape' : 'portrait') : 'unknown');
    const previous = lastStableOrientation;

    originalApplyRemoteGeometry(data);

    if (expected !== 'unknown' && expected !== previous) {
      refreshAfterOrientationChange(expected);
    } else {
      if (expected !== 'unknown') lastStableOrientation = expected;
      applyViewerGeometry();
      scheduleOrientationConsistencyCheck();
    }
  };

  function scheduleOrientationConsistencyCheck() {
    setTimeout(checkOrientationConsistency, 150);
    setTimeout(checkOrientationConsistency, 700);
    setTimeout(checkOrientationConsistency, 1300);
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
    if (now - lastGeometryRepairAt < 900 || repairAttempts >= 4) return;
    lastGeometryRepairAt = now;
    repairAttempts += 1;
    sendControl({ type:'capture_geometry_refresh' });
    if (repairAttempts === 2) sendControl({type:'capture_profile', profile:currentAdaptiveProfile});
  }

  function requestProfile(profile, reason) {
    if (!channelAuthenticated || !control || control.readyState !== 'open') return;
    if (profile === currentAdaptiveProfile) return;
    const now = Date.now();
    if (now - lastProfileChangeAt < 4500) return;
    lastProfileChangeAt = now;
    currentAdaptiveProfile = profile;
    sendControl({ type:'capture_profile', profile });
    if (reason) setMessage(reason, profile === 'balanced' ? 'success' : '');
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
        currentAdaptiveProfile = 'balanced';
        badQualityWindows = 0;
        goodQualityWindows = 0;
        lastPacketsReceived = 0;
        lastPacketsLost = 0;
        lastEmitted = 0;
        lastJitterDelay = 0;
        lastFramesDropped = 0;
        lastFramesDecoded = 0;
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            sendControl({ type:'capture_profile', profile:'balanced' });
            applyReceiverTarget(60);
            applyViewerGeometry();
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'display_geometry') {
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
      const framesDropped = Number(inbound.framesDropped || 0);
      const framesDecoded = Number(inbound.framesDecoded || 0);

      const deltaReceived = lastPacketsReceived > 0 ? Math.max(0, received - lastPacketsReceived) : 0;
      const deltaLost = lastPacketsLost > 0 ? Math.max(0, lost - lastPacketsLost) : 0;
      const deltaEmitted = lastEmitted > 0 ? Math.max(0, emitted - lastEmitted) : 0;
      const deltaJitterDelay = lastJitterDelay > 0 ? Math.max(0, jitterDelay - lastJitterDelay) : 0;
      const deltaDropped = lastFramesDropped > 0 ? Math.max(0, framesDropped - lastFramesDropped) : 0;
      const deltaDecoded = lastFramesDecoded > 0 ? Math.max(0, framesDecoded - lastFramesDecoded) : 0;
      lastPacketsReceived = received;
      lastPacketsLost = lost;
      lastEmitted = emitted;
      lastJitterDelay = jitterDelay;
      lastFramesDropped = framesDropped;
      lastFramesDecoded = framesDecoded;

      const packetWindow = deltaReceived + deltaLost;
      const lossPct = packetWindow > 0 ? (deltaLost / packetWindow) * 100 : 0;
      const bufferNowMs = deltaEmitted > 0 ? (deltaJitterDelay / deltaEmitted) * 1000 : 0;
      const frameWindow = deltaDecoded + deltaDropped;
      const dropPct = frameWindow > 0 ? (deltaDropped / frameWindow) * 100 : 0;

      let targetMs = 48;
      if (jitterMs > 26 || lossPct > 1.0) targetMs = 100;
      else if (jitterMs > 14 || lossPct > 0.35) targetMs = 78;
      else if (jitterMs > 8 || lossPct > 0.12) targetMs = 60;
      applyReceiverTarget(targetMs);

      const severe = bufferNowMs > 165 || jitterMs > 30 || lossPct > 1.0 || dropPct > 5;
      const pressured = bufferNowMs > 105 || jitterMs > 17 || lossPct > 0.35 || dropPct > 2;
      const healthy = bufferNowMs > 0 && bufferNowMs < 80 && jitterMs < 12 && lossPct < 0.25 && dropPct < 1;

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

      if (currentAdaptiveProfile === 'balanced' && badQualityWindows >= 2) {
        badQualityWindows = 0;
        requestProfile('economy', 'Vídeo pressionado: reduzindo carga para evitar artefatos e fila de atraso.');
      } else if (currentAdaptiveProfile === 'economy' && goodQualityWindows >= 6) {
        goodQualityWindows = 0;
        requestProfile('balanced', 'Transmissão estabilizada: restaurando qualidade balanceada.');
      }
    } catch {}
  }

  const originalStartStats = startStats;
  startStats = function() {
    originalStartStats();
    clearInterval(latencyTuningTimer);
    latencyTuningTimer = setInterval(retuneLatencyFromStats, 1200);
    retuneLatencyFromStats();

    const consistencyTimer = setInterval(() => {
      if (!pc) {
        clearInterval(consistencyTimer);
        return;
      }
      checkOrientationConsistency();
      applyViewerGeometry();
    }, 700);
  };

  window.addEventListener('resize', applyViewerGeometry);
  document.addEventListener('fullscreenchange', () => requestAnimationFrame(applyViewerGeometry));
})();
