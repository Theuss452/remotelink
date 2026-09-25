# Arquitetura — Internet MVP (v0.9.13-alpha)

## Princípio

Acesso via Internet **sem abrir porta no celular** e sem enfraquecer o
pareamento LAN. O `LocalControlServer` continua intacto e é o único caminho em
rede local; o modo internet adiciona um caminho paralelo de signaling de saída.

## Componentes

```
[Celular Android]                    [Servidor Internet]          [Browser Chrome]
 LocalControlServer (LAN, intacto)
 InternetSignalingClient --HTTPS saída--> Caddy :443 --proxy--> backend 127.0.0.1:42000
 WebRtcHost (LAN: host/UDP) <--- SDP/candidates via servidor --- app.js (polling)
 WebRtcHost (internet: STUN+TURN, TCP on) <--mídia DTLS/SRTP--> browser
 DeviceIdentityStore (pairId+segredo, TTL 5min preservado)      web/ (mesmo código LAN)
```

- **Android (novo):** `auth/DeviceIdentityStore.kt` (persistência da identidade
  forte), `network/InternetSignalingClient.kt` (OkHttp HTTPS, Bearer, mesmos
  limites da LAN).
- **Android (estendido, diff mínimo):** `WebRtcHost.createAnswer()` com overload
  `(iceServers, allowTcpRelay)` — LAN continua `emptyList()` + TCP DISABLED;
  `LanPolicy.normalizeRemoteIceCandidate(..., allowRelay=false)` — LAN continua
  host/UDP mesma-sub-rede; `PairingManager.attachIdentityStore()` — HMAC
  (`remotelink-pair-v1`), SAS (`remotelink-sas-v1`), lockout 5 e TTLs intactos;
  `MainActivity` com toggle mínimo + revogação (limpa o store).
- **Servidor (novo, stub):** `server/` — Caddy (HTTPS :443 → `127.0.0.1:42000` +
  estáticos) + coturn (3478/5349/443, relay cego com auth temporária).
- **Web (adaptado, sem fork):** `web/config.js` (`window.REMOTELINK_ICE_SERVERS`,
  preenchido em runtime via sessão autenticada); `app.js` lê a config e mantém
  o polling.

## O que NÃO mudou

`ScreenCaptureService`, `RemoteAccessibilityService`, sessão única, lockout 5,
TTL pairing 5min / sessão 30min / pending 90s, limites body 256KB / SDP 180K,
DataChannel auth sha256 + seq monotônica, watchdog 5s/30s.
