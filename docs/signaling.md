# Signaling — Internet MVP

## Modelo

Polling HTTPS autenticado (Bearer), espelhando os endpoints LAN. Sem WebSocket
público aberto: o celular faz `POST/GET` de saída; o browser faz polling
(300ms candidates, 700ms state — mesmos intervalos do `app.js`).

## Endpoints (backend em `127.0.0.1:42000`, atrás do Caddy)

| Método | Caminho | Auth | Corpo | Resposta |
|---|---|---|---|---|
| POST | `/api/webrtc/offer` | Bearer | `{sdp}` ≤180K | `{sdp: answer}` |
| POST | `/api/webrtc/candidate` | Bearer | `{candidate, sdpMid, sdpMLineIndex}` | `{}` |
| GET | `/api/webrtc/candidates` | Bearer | — | `{candidates: [...]}` (máx 64/poll) |
| GET | `/api/turn?deviceId=...` | Bearer | — | `{uris, username, password, ttl}` |

O pareamento (QR + HMAC `remotelink-pair-v1` + SAS `remotelink-sas-v1` +
aprovação física) é pré-requisito e não muda; o Bearer da sessão continua com
IP binding onde aplicável e TTL de 30min.

## Cliente Android

`InternetSignalingClient(baseUrl, bearerToken)`:

- Recusa `http://` (só `https://`); sem seguir redirects; timeouts 10s/15s/15s.
- Limites: resposta ≤256KB, SDP ≤180K, candidate ≤8KB — iguais à LAN.
- Erros genéricos (`signaling HTTP {code}`): corpo e token nunca em exceção/log.

## TURN REST (credencial temporária)

Servidor gera por sessão autenticada: `username = expiry:deviceId`,
`password = base64(HMAC-SHA256(static-auth-secret, username))`, TTL 3600s.
URIs anunciadas: `turn:`/`turns:` (MVP usa `turns:` 5349/443). O segredo
compartilhado **nunca** sai do servidor; o cliente só recebe a credencial de
1h via HTTPS autenticado.
