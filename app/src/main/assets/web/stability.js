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
  let currentAdaptiveProfile = 'balanced';
  let badQualityWindows = 0;
  let goodQualityWindows = 0;
  let lastProfileChangeAt = 0;
  let visualRotation = 0;
  let correctedDisplayRect = null;

  preferHardwareFriendlyVideoCodec = function() {};

  function applyReceiverTarget(targetMs) {
    if (!pc) return;
    const target = Math.max(45, Math.min(120, Math.round(targetMs)));
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
      if ('jitterBufferTarget' in receiver) receiver.jitterBufferTarget = 80;
    } catch {}
    try {
      if ('playoutDelayHint' in receiver) receiver.playoutDelayHint = 0.08;
    } catch {}
    return receiver;
  };

  function resetVideoCorrection() {
    if (!visualRotation && !correctedDisplayRect) return;
    visualRotation = 0;
    correctedDisplayRect = null;
    el.stage.classList.remove('orientation-fallback');
    for (const prop of ['left','top','right','bottom','inset','width','height','max-width','max-height','transform']) {
      el.video.style.removeProperty(prop);
    }
    // Reapply the normal no-crop layout from app.js.
    enforceAutoFit();
  }

  function applyVisualOrientationCorrection() {
    const vw = Number(el.video.videoWidth || 0);
    const vh = Number(el.video.videoHeight || 0);
    const stageW = Number(el.stage.clientWidth || 0);
    const stageH = Number(el.stage.clientHeight || 0);
    if (!vw || !vh || !stageW || !stageH || lastStableOrientation === 'unknown') {
      resetVideoCorrection();
      return;
    }

    const actual = vw > vh ? 'landscape' : 'portrait';
    if (actual === lastStableOrientation) {
      resetVideoCorrection();
      return;
    }

    // The Android projection can briefly (or on some OEMs permanently) expose a
    // portrait-shaped WebRTC frame while the physical screen is landscape. Instead
    // of zooming/cropping it, rotate the COMPLETE frame locally and fit the rotated
    // bounding box inside the existing viewer.
    const rotation = lastStableOrientation === 'landscape' && actual === 'portrait'
      ? 90
      : lastStableOrientation === 'portrait' && actual === 'landscape'
        ? -90
        : 0;
    if (!rotation) {
      resetVideoCorrection();
      return;
    }

    const rotatedSourceW = vh;
    const rotatedSourceH = vw;
    const scale = Math.min(stageW / rotatedSourceW, stageH / rotatedSourceH);
    const preRotateW = Math.max(1, vw * scale);
    const preRotateH = Math.max(1, vh * scale);
    const visibleW = Math.max(1, rotatedSourceW * scale);
    const visibleH = Math.max(1, rotatedSourceH * scale);

    visualRotation = rotation;
    el.stage.classList.add('orientation-fallback');
    el.video.style.setProperty('inset', 'auto', 'important');
    el.video.style.setProperty('left', '50%', 'important');
    el.video.style.setProperty('top', '50%', 'important');
    el.video.style.setProperty('right', 'auto', 'important');
    el.video.style.setProperty('bottom', 'auto', 'important');
    el.video.style.setProperty('width', `${preRotateW}px`, 'important');
    el.video.style.setProperty('height', `${preRotateH}px`, 'important');
    el.video.style.setProperty('max-width', 'none', 'important');
    el.video.style.setProperty('max-height', 'none', 'important');
    el.video.style.setProperty('object-fit', 'contain', 'important');
    el.video.style.setProperty('object-position', '50% 50%', 'important');
    el.video.style.setProperty('transform', `translate(-50%, -50%) rotate(${rotation}deg)`, 'important');

    const stageRect = el.stage.getBoundingClientRect();
    correctedDisplayRect = {
      left: stageRect.left + (stageW - visibleW) / 2,
      top: stageRect.top + (stageH - visibleH) / 2,
      width: visibleW,
      height: visibleH
    };
  }

  const originalNormalizedPoint = normalizedPoint;
  normalizedPoint = function(clientX, clientY) {
    if (!visualRotation || !correctedDisplayRect) return originalNormalizedPoint(clientX, clientY);
    const r = correctedDisplayRect;
    const x = (clientX - r.left) / r.width;
    const y = (clientY - r.top) / r.height;
    if (x < 0 || x > 1 || y < 0 || y > 1) return null;
    // The corrected image is displayed in the physical Android orientation, so the
    // normalized point is already the coordinate expected by AccessibilityService.
    return { x, y };
  };

  const originalUpdateViewerGeometry = updateViewerGeometry;
  updateViewerGeometry = function(force = false) {
    originalUpdateViewerGeometry(force);
    requestAnimationFrame(applyVisualOrientationCorrection);
  };

  const originalApplyRemoteGeometry = applyRemoteGeometry;
  applyRemoteGeometry = function(data) {
    originalApplyRemoteGeometry(data);
    const sourceW = Number(data?.captureContentWidth || data?.displayWidth || 0);
    const sourceH = Number(data?.captureContentHeight || data?.displayHeight || 0);
    const expected = data?.orientation || (sourceW && sourceH ? (sourceW > sourceH ? 'landscape' : 'portrait') : 'unknown');
    if (expected !== 'unknown') lastStableOrientation = expected;
    requestAnimationFrame(applyVisualOrientationCorrection);
    scheduleOrientationConsistencyCheck();
  };

  function scheduleOrientationConsistencyCheck() {
    setTimeout(checkOrientationConsistency, 100);
    setTimeout(checkOrientationConsistency, 500);
    setTimeout(checkOrientationConsistency, 1000);
  }

  function checkOrientationConsistency() {
    if (!channelAuthenticated || !control || control.readyState !== 'open') return;
    const vw = Number(el.video.videoWidth || 0);
    const vh = Number(el.video.videoHeight || 0);
    if (!vw || !vh || lastStableOrientation === 'unknown') return;

    const actual = vw > vh ? 'landscape' : 'portrait';
    if (actual === lastStableOrientation) {
      repairAttempts = 0;
      resetVideoCorrection();
      return;
    }

    // Keep the UI usable immediately, even if a device ignores changeCaptureFormat().
    applyVisualOrientationCorrection();

    const now = Date.now();
    if (now - lastGeometryRepairAt < 700 || repairAttempts >= 6) return;
    lastGeometryRepairAt = now;
    repairAttempts += 1;
    sendControl({ type:'capture_geometry_refresh' });
    setMessage('Ajustando rotação sem recortar a tela…');
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
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            sendControl({ type:'capture_profile', profile:'balanced' });
            applyReceiverTarget(80);
            applyVisualOrientationCorrection();
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'display_geometry') {
        requestAnimationFrame(applyVisualOrientationCorrection);
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

      let targetMs = 55;
      if (jitterMs > 25 || lossPct > 1.0) targetMs = 115;
      else if (jitterMs > 12 || lossPct > 0.3) targetMs = 85;
      else if (jitterMs > 7 || lossPct > 0.1) targetMs = 70;
      applyReceiverTarget(targetMs);

      const severe = bufferNowMs > 180 || jitterMs > 32 || lossPct > 1.2;
      const pressured = bufferNowMs > 125 || jitterMs > 22 || lossPct > 0.6;
      const healthy = bufferNowMs > 0 && bufferNowMs < 90 && jitterMs < 14 && lossPct < 0.35;

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

      if (currentAdaptiveProfile === 'balanced' && badQualityWindows >= 3) {
        badQualityWindows = 0;
        requestProfile('economy', 'Rede local pressionada: reduzindo vídeo temporariamente para cortar a fila de atraso.');
      } else if (currentAdaptiveProfile === 'economy' && goodQualityWindows >= 5) {
        goodQualityWindows = 0;
        requestProfile('balanced', 'Rede estabilizada: restaurando qualidade balanceada.');
      }
    } catch {}
  }

  const originalStartStats = startStats;
  startStats = function() {
    originalStartStats();
    clearInterval(latencyTuningTimer);
    latencyTuningTimer = setInterval(retuneLatencyFromStats, 1500);
    retuneLatencyFromStats();

    const consistencyTimer = setInterval(() => {
      if (!pc) {
        clearInterval(consistencyTimer);
        return;
      }
      checkOrientationConsistency();
      applyVisualOrientationCorrection();
    }, 800);
  };

  try {
    new ResizeObserver(() => requestAnimationFrame(applyVisualOrientationCorrection)).observe(el.stage);
  } catch {}
  document.addEventListener('fullscreenchange', () => requestAnimationFrame(applyVisualOrientationCorrection));
})();
