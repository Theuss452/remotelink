'use strict';

(() => {
  const MAX_BUFFERED_BYTES = 1024 * 1024;
  const LOW_BUFFERED_BYTES = 256 * 1024;
  const CHUNK_BYTES = 32 * 1024;

  let fileChannel = null;
  let filePeer = null;
  let fileAuthenticated = false;
  let maxFileBytes = 100 * 1024 * 1024;
  let activeTransfer = null;
  let readyResolve = null;
  let readyReject = null;
  let savedResolve = null;
  let savedReject = null;

  const ui = buildUi();
  installCreateOfferHook();

  function buildUi() {
    const side = document.getElementById('sideColumn');
    if (!side) return {};

    const quality = document.createElement('section');
    quality.className = 'panel text-panel remotelink-tools';
    quality.innerHTML = `
      <div class="section-head"><div><span class="eyebrow">DESEMPENHO</span><h3>Qualidade da transmissão</h3></div><span class="mini-pill">v0.6</span></div>
      <p>Fluido tenta 60 FPS com resolução moderada para reduzir a sensação de atraso. Alta prioriza definição.</p>
      <div class="tool-row">
        <select id="captureProfile" disabled aria-label="Perfil de qualidade">
          <option value="auto">Automático • 30 FPS</option>
          <option value="fluid">Fluido • até 60 FPS</option>
          <option value="balanced">Balanceado • 30 FPS</option>
          <option value="high">Alta qualidade • 30 FPS</option>
          <option value="economy">Economia • 20 FPS</option>
        </select>
        <span id="profileState" class="tool-state">Automático</span>
      </div>`;

    const files = document.createElement('section');
    files.className = 'panel text-panel remotelink-tools';
    files.innerHTML = `
      <div class="section-head"><div><span class="eyebrow">ARQUIVOS</span><h3>Enviar para o Android</h3></div><span id="fileSecureBadge" class="mini-pill">DTLS</span></div>
      <p>O arquivo viaja pelo WebRTC da sessão aprovada e é salvo em <strong>Downloads/RemoteLink</strong>. Limite atual: 100 MB.</p>
      <input id="remoteFileInput" class="file-input" type="file" disabled>
      <button id="sendFileButton" class="secondary wide-button" type="button" disabled>Enviar arquivo</button>
      <div class="transfer-progress"><span id="fileProgressBar"></span></div>
      <small id="fileTransferState" class="transfer-state">Aguardando conexão segura…</small>`;

    const firstSecurity = side.querySelector('.session-panel');
    if (firstSecurity) {
      side.insertBefore(files, firstSecurity);
      side.insertBefore(quality, files);
    } else {
      side.appendChild(quality);
      side.appendChild(files);
    }

    const profile = quality.querySelector('#captureProfile');
    const profileState = quality.querySelector('#profileState');
    const fileInput = files.querySelector('#remoteFileInput');
    const sendFile = files.querySelector('#sendFileButton');
    const progress = files.querySelector('#fileProgressBar');
    const transferState = files.querySelector('#fileTransferState');

    profile?.addEventListener('change', () => {
      if (!channelAuthenticated || !controlReady) return;
      const value = profile.value;
      if (sendControl({ type: 'capture_profile', profile: value })) {
        profileState.textContent = profile.options[profile.selectedIndex]?.textContent || value;
        setMessage(`Perfil de transmissão alterado para ${profileState.textContent}.`, 'success');
      }
    });

    fileInput?.addEventListener('change', () => {
      const file = fileInput.files?.[0];
      if (!file) {
        transferState.textContent = 'Selecione um arquivo.';
        return;
      }
      transferState.textContent = `${file.name} • ${formatBytes(file.size)}`;
    });

    sendFile?.addEventListener('click', async () => {
      const file = fileInput?.files?.[0];
      if (!file) return setTransferState('Selecione um arquivo primeiro.', true);
      try {
        await sendFileToAndroid(file);
      } catch (error) {
        setTransferState(error?.message || 'Falha ao enviar arquivo.', true);
      }
    });

    return { quality, files, profile, profileState, fileInput, sendFile, progress, transferState };
  }

  function installCreateOfferHook() {
    const proto = window.RTCPeerConnection?.prototype;
    if (!proto || proto.__remoteLinkFileHook) return;
    const nativeCreateOffer = proto.createOffer;
    Object.defineProperty(proto, '__remoteLinkFileHook', { value: true });
    proto.createOffer = function(...args) {
      try {
        if (this === pc && (!fileChannel || filePeer !== this || fileChannel.readyState === 'closed')) {
          filePeer = this;
          fileAuthenticated = false;
          fileChannel = this.createDataChannel('file', { ordered: true });
          bindFileChannel(fileChannel);
        }
      } catch (error) {
        console.warn('RemoteLink: canal de arquivos indisponível', error);
      }
      return nativeCreateOffer.apply(this, args);
    };
  }

  function bindFileChannel(channel) {
    channel.binaryType = 'arraybuffer';
    channel.bufferedAmountLowThreshold = LOW_BUFFERED_BYTES;
    channel.onopen = () => authenticateFileChannel(channel);
    channel.onclose = () => {
      fileAuthenticated = false;
      setFileUiEnabled(false);
      if (activeTransfer) rejectTransfer(new Error('Canal de arquivos foi fechado.'));
    };
    channel.onerror = () => {
      if (activeTransfer) rejectTransfer(new Error('Erro no canal de arquivos.'));
    };
    channel.onmessage = event => {
      if (typeof event.data !== 'string') return;
      let data;
      try { data = JSON.parse(event.data); } catch { return; }
      if (data.type === 'hello') {
        if (Number.isFinite(Number(data.maxFileBytes))) maxFileBytes = Number(data.maxFileBytes);
        authenticateFileChannel(channel);
      } else if (data.type === 'auth_ok') {
        fileAuthenticated = true;
        if (Number.isFinite(Number(data.maxFileBytes))) maxFileBytes = Number(data.maxFileBytes);
        setFileUiEnabled(true);
        setTransferState(`Canal seguro pronto • limite ${formatBytes(maxFileBytes)}`);
      } else if (data.type === 'auth_failed') {
        fileAuthenticated = false;
        setFileUiEnabled(false);
        setTransferState('Autenticação do canal de arquivos falhou.', true);
      } else if (data.type === 'file_ready' && activeTransfer?.id === data.id) {
        readyResolve?.(); clearReadyPromise();
      } else if (data.type === 'file_saved' && activeTransfer?.id === data.id) {
        savedResolve?.(data); clearSavedPromise();
      } else if (data.type === 'file_error') {
        const error = new Error(fileErrorLabel(data.error));
        readyReject?.(error); savedReject?.(error);
        clearReadyPromise(); clearSavedPromise();
      }
    };
  }

  function authenticateFileChannel(channel) {
    if (!token || channel.readyState !== 'open') return;
    channel.send(JSON.stringify({ type: 'auth', token }));
  }

  async function sendFileToAndroid(file) {
    if (!fileAuthenticated || !fileChannel || fileChannel.readyState !== 'open') {
      throw new Error('Canal seguro de arquivos ainda não está pronto.');
    }
    if (activeTransfer) throw new Error('Já existe uma transferência em andamento.');
    if (file.size <= 0) throw new Error('O arquivo está vazio.');
    if (file.size > maxFileBytes) throw new Error(`Arquivo maior que o limite de ${formatBytes(maxFileBytes)}.`);

    const id = makeTransferId();
    activeTransfer = { id, file };
    setFileUiEnabled(false, true);
    setProgress(0);
    setTransferState(`Preparando ${file.name}…`);

    try {
      const ready = new Promise((resolve, reject) => { readyResolve = resolve; readyReject = reject; });
      fileChannel.send(JSON.stringify({
        type: 'file_begin', id, name: file.name, size: file.size,
        mime: file.type || 'application/octet-stream'
      }));
      await withTimeout(ready, 8000, 'O Android não confirmou o início da transferência.');

      let offset = 0;
      while (offset < file.size) {
        if (!fileChannel || fileChannel.readyState !== 'open') throw new Error('Canal de arquivos desconectado.');
        if (fileChannel.bufferedAmount > MAX_BUFFERED_BYTES) await waitForBufferedLow(fileChannel);
        const end = Math.min(offset + CHUNK_BYTES, file.size);
        const chunk = await file.slice(offset, end).arrayBuffer();
        fileChannel.send(chunk);
        offset = end;
        setProgress(offset / file.size);
        setTransferState(`Enviando ${file.name} • ${Math.round((offset / file.size) * 100)}% • ${formatBytes(offset)} / ${formatBytes(file.size)}`);
      }

      while (fileChannel.bufferedAmount > LOW_BUFFERED_BYTES) await waitForBufferedLow(fileChannel);
      const saved = new Promise((resolve, reject) => { savedResolve = resolve; savedReject = reject; });
      fileChannel.send(JSON.stringify({ type: 'file_end', id }));
      const result = await withTimeout(saved, 15000, 'O Android recebeu os dados, mas não confirmou o salvamento.');
      setProgress(1);
      setTransferState(`${result.name || file.name} salvo em Downloads/RemoteLink.`, false, true);
      if (ui.fileInput) ui.fileInput.value = '';
    } catch (error) {
      try { fileChannel?.send(JSON.stringify({ type: 'file_cancel', id })); } catch {}
      throw error;
    } finally {
      activeTransfer = null;
      clearReadyPromise(); clearSavedPromise();
      setFileUiEnabled(fileAuthenticated);
    }
  }

  function waitForBufferedLow(channel) {
    if (channel.bufferedAmount <= LOW_BUFFERED_BYTES) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { cleanup(); reject(new Error('A transferência ficou congestionada.')); }, 10000);
      const onLow = () => { cleanup(); resolve(); };
      const onClose = () => { cleanup(); reject(new Error('Canal fechado durante a transferência.')); };
      function cleanup() {
        clearTimeout(timeout);
        channel.removeEventListener('bufferedamountlow', onLow);
        channel.removeEventListener('close', onClose);
      }
      channel.addEventListener('bufferedamountlow', onLow, { once: true });
      channel.addEventListener('close', onClose, { once: true });
    });
  }

  function setFileUiEnabled(enabled, transferring = false) {
    if (ui.fileInput) ui.fileInput.disabled = !enabled || transferring;
    if (ui.sendFile) ui.sendFile.disabled = !enabled || transferring;
    if (ui.profile) ui.profile.disabled = !channelAuthenticated;
  }

  function setTransferState(text, error = false, success = false) {
    if (!ui.transferState) return;
    ui.transferState.textContent = text;
    ui.transferState.classList.toggle('error', error);
    ui.transferState.classList.toggle('success', success);
  }

  function setProgress(value) {
    if (ui.progress) ui.progress.style.width = `${Math.max(0, Math.min(1, value)) * 100}%`;
  }

  function rejectTransfer(error) {
    readyReject?.(error); savedReject?.(error);
    clearReadyPromise(); clearSavedPromise();
    activeTransfer = null;
    setFileUiEnabled(false);
  }

  function clearReadyPromise() { readyResolve = null; readyReject = null; }
  function clearSavedPromise() { savedResolve = null; savedReject = null; }

  function withTimeout(promise, ms, message) {
    return Promise.race([
      promise,
      new Promise((_, reject) => setTimeout(() => reject(new Error(message)), ms))
    ]);
  }

  function makeTransferId() {
    if (crypto.randomUUID) return crypto.randomUUID().replace(/-/g, '');
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  }

  function fileErrorLabel(error) {
    return ({
      transfer_busy: 'O Android já está recebendo outro arquivo.',
      invalid_id: 'Identificador de transferência inválido.',
      invalid_size: 'Tamanho de arquivo rejeitado pelo Android.',
      invalid_name: 'Nome de arquivo inválido.',
      invalid_chunk: 'Bloco de dados inválido.',
      size_overflow: 'Foram recebidos mais dados que o tamanho declarado.',
      size_mismatch: 'A transferência terminou com tamanho diferente do esperado.',
      save_failed: 'O Android não conseguiu salvar o arquivo.'
    })[error] || `Falha na transferência (${error || 'erro desconhecido'}).`;
  }

  function formatBytes(bytes) {
    const n = Number(bytes) || 0;
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  }

  const syncUiTimer = setInterval(() => {
    if (ui.profile) ui.profile.disabled = !channelAuthenticated;
    if (!fileAuthenticated && (!fileChannel || fileChannel.readyState !== 'open')) setFileUiEnabled(false);
  }, 1000);

  window.addEventListener('pagehide', () => {
    clearInterval(syncUiTimer);
    try { if (activeTransfer) fileChannel?.send(JSON.stringify({ type: 'file_cancel', id: activeTransfer.id })); } catch {}
    try { fileChannel?.close(); } catch {}
  });
})();
