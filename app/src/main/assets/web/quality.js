'use strict';

(() => {
  const modeSelect = document.querySelector('#qualityMode');
  const customBox = document.querySelector('#customQualityFields');
  const edgeSelect = document.querySelector('#customMaxEdge');
  const fpsSelect = document.querySelector('#customFps');
  const bitrateInput = document.querySelector('#customBitrate');
  const bitrateValue = document.querySelector('#customBitrateValue');
  const applyButton = document.querySelector('#applyQualityButton');
  const live = document.querySelector('#qualityLive');
  const network = document.querySelector('#qualityNetwork');
  if (!modeSelect || !customBox || !edgeSelect || !fpsSelect || !bitrateInput || !applyButton) return;

  const STORAGE_KEY = 'remotelink_quality_v2';
  const defaults = { mode:'auto', maxEdge:1280, fps:30, bitrateMbps:4.8 };
  let settings = loadSettings();
  let serverProfile = 'auto';
  let serverAutoTier = 'balanced';
  let lastNetwork = null;

  modeSelect.value = settings.mode;
  edgeSelect.value = String(settings.maxEdge);
  fpsSelect.value = String(settings.fps);
  bitrateInput.value = String(settings.bitrateMbps);
  updateBitrateLabel();
  updateCustomVisibility();
  renderLive();

  modeSelect.addEventListener('change', () => {
    settings.mode = validMode(modeSelect.value) ? modeSelect.value : 'auto';
    updateCustomVisibility();
    saveSettings();
    applyDesiredQuality(true);
  });

  edgeSelect.addEventListener('change', saveCustomFromUi);
  fpsSelect.addEventListener('change', saveCustomFromUi);
  bitrateInput.addEventListener('input', () => {
    updateBitrateLabel();
    saveCustomFromUi(false);
  });
  bitrateInput.addEventListener('change', () => saveCustomFromUi(true));
  applyButton.addEventListener('click', () => {
    saveCustomFromUi(false);
    applyDesiredQuality(true);
  });

  function validMode(value) {
    return ['auto','economy','balanced','high','fluid','fluid60','custom'].includes(value);
  }

  function clampNumber(value, min, max, fallback) {
    const n = Number(value);
    return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
  }

  function loadSettings() {
    try {
      const parsed = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}');
      return {
        mode: validMode(parsed.mode) ? parsed.mode : defaults.mode,
        maxEdge: Math.round(clampNumber(parsed.maxEdge, 480, 2560, defaults.maxEdge)),
        fps: Math.round(clampNumber(parsed.fps, 15, 60, defaults.fps)),
        bitrateMbps: Math.round(clampNumber(parsed.bitrateMbps, .6, 20, defaults.bitrateMbps) * 10) / 10
      };
    } catch {
      return {...defaults};
    }
  }

  function saveSettings() {
    try { sessionStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); } catch {}
  }

  function saveCustomFromUi(apply = false) {
    settings.maxEdge = Math.round(clampNumber(edgeSelect.value, 480, 2560, defaults.maxEdge));
    settings.fps = Math.round(clampNumber(fpsSelect.value, 15, 60, defaults.fps));
    settings.bitrateMbps = Math.round(clampNumber(bitrateInput.value, .6, 20, defaults.bitrateMbps) * 10) / 10;
    saveSettings();
    renderLive();
    if (apply && settings.mode === 'custom') applyDesiredQuality(true);
  }

  function updateBitrateLabel() {
    if (bitrateValue) bitrateValue.textContent = `${Number(bitrateInput.value).toFixed(1)} Mb/s`;
  }

  function updateCustomVisibility() {
    customBox.classList.toggle('hidden', settings.mode !== 'custom');
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

  function applyDesiredQuality(showFeedback = false) {
    if (!channelAuthenticated || !control || control.readyState !== 'open') {
      if (showFeedback) renderLive('Será aplicada quando a conexão estiver pronta.');
      return false;
    }

    let sent;
    if (settings.mode === 'custom') {
      sent = sendSessionCommand({
        type:'capture_custom',
        maxEdge:settings.maxEdge,
        fps:settings.fps,
        maxBitrateBps:Math.round(settings.bitrateMbps * 1_000_000)
      });
    } else {
      sent = sendSessionCommand({type:'capture_profile', profile:settings.mode});
    }
    if (sent && showFeedback) renderLive('Aplicando configuração…');
    return sent;
  }

  function prettyMode(mode) {
    return ({
      auto:'Auto adaptativo', economy:'Econômico', balanced:'Balanceado', high:'Alta qualidade',
      fluid:'Fluido 45 FPS', fluid60:'Fluido 60 FPS', custom:'Personalizado'
    })[mode] || mode;
  }

  function prettyTier(tier) {
    return ({economy:'economia', balanced:'30 FPS', fluid:'45 FPS'})[tier] || tier;
  }

  function renderLive(temporaryText = '') {
    if (!live) return;
    if (temporaryText) {
      live.textContent = temporaryText;
      return;
    }
    const mode = settings.mode;
    const detail = mode === 'custom'
      ? `${settings.maxEdge}px máx. • ${settings.fps} FPS • ${settings.bitrateMbps.toFixed(1)} Mb/s`
      : mode === 'auto'
        ? `tier atual: ${prettyTier(serverAutoTier)}`
        : 'perfil fixo';
    live.textContent = `${prettyMode(mode)} • ${detail}`;
  }

  function renderNetwork() {
    if (!network) return;
    const n = lastNetwork;
    if (!n) {
      network.textContent = 'Aguardando telemetria da conexão…';
      return;
    }
    const details = [
      `FPS ${Number(n.fps || 0).toFixed(0)}`,
      `drop ${Number(n.dropPct || 0).toFixed(1)}%`,
      `decode ${Number(n.decodeMs || 0).toFixed(1)} ms`,
      `jitter ${Number(n.jitterMs || 0).toFixed(0)} ms`,
      `perda ${Number(n.lossPct || 0).toFixed(2)}%`,
      `buffer ${Number(n.bufferMs || 0).toFixed(0)} ms`,
      `alvo ${Number(n.targetMs || 0).toFixed(0)} ms`
    ];
    if ((n.pli || 0) > 0 || (n.nack || 0) > 0) details.push(`NACK/PLI ${n.nack || 0}/${n.pli || 0}`);
    network.textContent = details.join(' • ');
  }

  const originalBindControlChannel = bindControlChannel;
  bindControlChannel = function(channel) {
    originalBindControlChannel(channel);
    const previousMessage = channel.onmessage;
    channel.onmessage = ev => {
      let data = null;
      try { data = JSON.parse(ev.data); } catch {}
      previousMessage?.call(channel, ev);

      if (data?.type === 'auth_ok') setTimeout(() => applyDesiredQuality(false), 120);
      if (data?.type === 'capture_profile_result' && data.ok) {
        serverProfile = data.profile || settings.mode;
        if (settings.mode === 'auto' && ['economy','balanced','fluid'].includes(serverProfile)) serverAutoTier = serverProfile;
        else serverAutoTier = data.autoTier || serverAutoTier;
        renderLive();
      }
      if (data?.type === 'capture_custom_result') {
        if (data.ok) {
          serverProfile = 'custom';
          renderLive();
        } else renderLive('Configuração personalizada recusada pelos limites de segurança.');
      }
      if (data?.type === 'capture_auto_tier_result' && data.ok) {
        serverProfile = data.profile || 'auto';
        serverAutoTier = data.autoTier || serverAutoTier;
        renderLive();
      }
      if (data?.type === 'display_geometry') {
        serverProfile = data.profile || serverProfile;
        if (settings.mode === 'auto' && ['economy','balanced','fluid'].includes(serverProfile)) serverAutoTier = serverProfile;
        else serverAutoTier = data.autoTier || serverAutoTier;
        renderLive();
      }
    };
  };

  window.RemoteLinkQuality = {
    mode: () => settings.mode,
    apply: () => applyDesiredQuality(false),
    updateAutoTier: tier => {
      serverAutoTier = tier;
      renderLive();
    },
    updateNetworkStats: stats => {
      lastNetwork = stats;
      renderNetwork();
    }
  };

  renderNetwork();
})();

// Hard viewer guard: no profile, rotation fallback or stale CSS class is allowed to crop the
// remote frame. Inline !important wins over the legacy frame-letterbox-fix stylesheet rule.
(() => {
  const originalEnforceAutoFit = enforceAutoFit;
  enforceAutoFit = function() {
    originalEnforceAutoFit();
    const video = el?.video;
    if (!video) return;
    video.style.setProperty('object-fit', 'contain', 'important');
    video.style.setProperty('object-position', '50% 50%', 'important');
    video.style.setProperty('width', '100%', 'important');
    video.style.setProperty('height', '100%', 'important');
    video.style.setProperty('max-width', '100%', 'important');
    video.style.setProperty('max-height', '100%', 'important');
    video.style.setProperty('transform', 'none', 'important');
    el.stage?.classList.remove('frame-letterbox-fix');
    document.documentElement.classList.remove('frame-letterbox-fix');
  };
  enforceAutoFit();
})();
