'use strict';
/* RemoteLink Internet MVP — configuração do web hospedado (HTTPS).
 *
 * COMO USAR: copie app/src/main/assets/web/* para o host estático servido pelo
 * Caddy (ver server/Caddyfile) e gere este arquivo no deploy com os ICE servers
 * reais. NUNCA commite credencial TURN aqui: o browser obtém username/password
 * temporários via endpoint autenticado (sessão Bearer) e este objeto é
 * preenchido em runtime — os valores abaixo são apenas o fallback vazio (LAN).
 *
 * Em runtime o app.js lê window.REMOTELINK_ICE_SERVERS (polling inalterado).
 */
window.REMOTELINK_ICE_SERVERS = window.REMOTELINK_ICE_SERVERS || [];
window.REMOTELINK_CONFIG = Object.assign(
  { signalingBase: '', turnEndpoint: '/api/turn' },
  window.REMOTELINK_CONFIG || {}
);
