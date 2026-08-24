'use strict';

(() => {
  const MAX_RETRIES = 3;
  const BASE_DELAY_MS = 1200;
  let retries = 0;
  let reconnectTimer = null;
  let reconnecting = false;

  const originalUpdateConnectionState = updateConnectionState;

  async function retryWebRtc() {
    if (reconnecting || !token || retries >= MAX_RETRIES) return;
    reconnecting = true;
    retries += 1;
    const delay = BASE_DELAY_MS * retries;
    setMessage(`Conexão instável. Tentando reconectar (${retries}/${MAX_RETRIES})…`);
    setConnectOverlay(true, `Nova tentativa em ${(delay / 1000).toFixed(1)} s…`);

    await sleep(delay);
    if (!token) {
      reconnecting = false;
      return;
    }

    try {
      await startWebRtc();
    } catch (error) {
      reconnecting = false;
      if (retries < MAX_RETRIES && token) {
        retryWebRtc();
      } else {
        setConnectOverlay(false);
        setMessage('A reconexão automática falhou. A sessão continua autorizada; tente conectar novamente.', 'error');
        el.pairButton.disabled = false;
        el.pairButton.textContent = 'Reconectar';
      }
      return;
    }

    reconnecting = false;
  }

  updateConnectionState = function(state) {
    originalUpdateConnectionState(state);

    if (state === 'connected') {
      retries = 0;
      reconnecting = false;
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
      return;
    }

    if ((state === 'failed' || state === 'disconnected') && token && !reconnecting) {
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(() => retryWebRtc(), state === 'disconnected' ? 1800 : 300);
    }
  };

  window.addEventListener('online', () => {
    if (token && pc && pc.connectionState !== 'connected') retryWebRtc();
  });
})();
