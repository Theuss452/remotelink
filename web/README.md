# Web hospedado (Internet MVP)

O web hospedado é o **mesmo código** de `app/src/main/assets/web/` (sem fork),
servido via HTTPS pelo Caddy (ver `server/Caddyfile`).

## Deploy

1. Copie `app/src/main/assets/web/*` para o diretório estático do servidor.
2. Adicione `config.js` (neste diretório) por cima — ele define
   `window.REMOTELINK_ICE_SERVERS` (fallback `[]` = LAN) e
   `window.REMOTELINK_CONFIG.signalingBase`.
3. Em runtime, após o pareamento aprovado (Bearer da sessão), o browser busca
   credencial TURN temporária em `GET {signalingBase}/api/turn?deviceId=...` e
   preenche `window.REMOTELINK_ICE_SERVERS` **antes** de criar o
   `RTCPeerConnection`. O polling de signaling (`/api/webrtc/*`) é idêntico ao
   da LAN, só que sob HTTPS.

## Regras

- HTTPS obrigatório; sem `http://`.
- Sem token/senha fixos em JS — só credencial TURN de ~1h via sessão autenticada.
- Sem CDN externo: só assets locais (mesma CSP da LAN).
