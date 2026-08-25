'use strict';

(() => {
  let lastGeometryRepairAt = 0;
  let repairAttempts = 0;
  let lastStableOrientation = 'unknown';

  // Do not force a codec from the browser. Let WebRTC negotiate the best common
  // hardware-backed codec for the Android/Chromebook pair. Forcing H.264 caused
  // severe block artifacts on some devices under congestion.
  preferHardwareFriendlyVideoCodec = function() {};

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
        // Stable default. 30 fps avoids a long encode/jitter queue on slower Wi-Fi,
        // while 1280 keeps text/UI readable. Fluid remains available separately.
        setTimeout(() => {
          if (channelAuthenticated && control?.readyState === 'open') {
            sendControl({ type:'capture_profile', profile:'balanced' });
          }
        }, 80);
        scheduleOrientationConsistencyCheck();
      }

      if (data?.type === 'display_geometry') scheduleOrientationConsistencyCheck();
    };
  };

  const originalStartStats = startStats;
  startStats = function() {
    originalStartStats();
    const consistencyTimer = setInterval(() => {
      if (!pc) {
        clearInterval(consistencyTimer);
        return;
      }
      checkOrientationConsistency();
    }, 1000);
  };
})();
