'use strict';

(() => {
  let lastGeometryRepairAt = 0;
  let repairAttempts = 0;
  let lastStableOrientation = 'unknown';
  let latencyTuningTimer = null;
  let lastPacketsReceived = 0;
  let lastPacketsLost = 0;

  // Let WebRTC negotiate the best common codec instead of forcing H.264.
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

  // Zero buffering caused visible micro-freezes. Chromium's completely unconstrained
  // adaptive buffer, however, grew to ~250 ms on a noisy LAN. Start in the middle.
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

  const originalApplyRemoteGeometry = applyRemoteGeometry;
  applyRemoteGeometry = function(data) {
    originalApplyRemoteGeometry(data);
    const sourceW = Number(data?.captureContentWidth || data?.displayWidth || 0);
    const sourceH = Number(data?.captureContentHeight || data?.displayHeight || 0);
    const expected = data?.orientation || (sourceW && sourceH ? (sourceW > sourceH ? 'landscape' : 'portrait') : 'unknown');
    if (expected !== 'unknown') lastStableOrientation = expected;
    scheduleOrientationConsistencyCheck();
  };

  function scheduleOrientationConsistencyCheck() {
    setTimeout(checkOrientationConsistency, 120);
    setTimeout(checkOrientationConsistency, 550);
  }

  function checkOrientationConsistency() {
    if (!channelAuthenticated || !control || control.readyState !== 'open') return;
    const vw = Number(el.video.videoWidth || 0);
    const vh = Number(el.video.videoHeight || 0);
    if (!vw || !vh || lastStableOrientation === 'unknown') return;

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
    setMessage(`Corrigindo orientação da captura (${repairAttempts}/4)…`);
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
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            sendControl({ type:'capture_profile', profile:'balanced' });
            applyReceiverTarget(80);
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'display_geometry') scheduleOrientationConsistencyCheck();
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
      const deltaReceived = Math.max(0, received - lastPacketsReceived);
      const deltaLost = Math.max(0, lost - lastPacketsLost);
      lastPacketsReceived = received;
      lastPacketsLost = lost;
      const packetWindow = deltaReceived + deltaLost;
      const lossPct = packetWindow > 0 ? (deltaLost / packetWindow) * 100 : 0;

      let targetMs = 55;
      if (jitterMs > 25 || lossPct > 1.0) targetMs = 120;
      else if (jitterMs > 12 || lossPct > 0.3) targetMs = 85;
      else if (jitterMs > 7 || lossPct > 0.1) targetMs = 70;
      applyReceiverTarget(targetMs);
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
    }, 1000);
  };
})();
