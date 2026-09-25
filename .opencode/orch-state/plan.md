# RemoteLink Internet MVP — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Habilitar acesso via Internet preservando pairing forte LAN (QR+HMAC/SAS+aprovação física), DTLS/SRTP, TURN como relay cego com credencial temporária, sem porta pública no celular, sessão única.

**Architecture:** Manter LocalControlServer LAN intacto; adicionar InternetSignalingClient (OkHttp HTTPS) + DeviceIdentityStore (EncryptedSharedPreferences) no Android; estender WebRtcHost para IceServers dinâmicos + TCP habilitado em modo internet; LanPolicy com flag allowRelay (LAN continua host/udp only); novo server/ com Caddy reverse-proxy HTTPS 443 + coturn 3478/5349/443-TLS; web hosted HTTPS adaptado de assets/web.

**Tech Stack:** Kotlin, Gradle 8.13, Java 17, OkHttp 4.12.0, libwebrtc 144.7559.12, coturn, Caddy, Docker.

**Spec:** C:\Users\matheus\Downloads\remotelink\Instruçoesremotelink.txt + facts.md. MVP primeiro: HTTPS + login/pairing + signaling + P2P + TURN fallback + Chrome sem instalação.

## Global Constraints
- Preservar LocalControlServer, PairingManager forte, WebRTC DTLS/SRTP, 5 tentativas, TTL 5min pairing / 30min sessão / 90s pending, body 256KB / SDP 180K.
- Nunca: porta pública→celular, senha simples, token fixo JS, senha permanente APK, TURN sem auth, WS público aberto, CORS aberto, bypass MediaProjection/Accessibility, cripto própria, MJPEG.
- Comandos reais do adapter java-gradle (Windows: gradlew.bat). Build oficial no Actions com assembleDirectDebug.
- Version: versionCode 27→28, versionName 0.9.12-alpha→0.9.13-alpha, workflow título Build RemoteLink v0.9.13-alpha.

---
### Task 1: DeviceIdentityStore
**Files:**
- Create: `app/src/main/java/app/remotelink/auth/DeviceIdentityStore.kt`
- Test: leitura estática (sem SDK local) + Actions
**Interfaces:**
- Consumes: PairingManager.newCode/verifyStrong/issueSessionToken
- Produces: loadIdentity()/saveIdentity(pairId, secretB64)/clear() thread-safe via EncryptedSharedPreferences
- [ ] Step 1: Criar DeviceIdentityStore com get/set thread-safe, zeroing de secret em clear()
- [ ] Step 2: Validar por leitura (imports, API EncryptedSharedPreferences existente no projeto? se não, fallback SharedPreferences + nota)
- [ ] Step 3: Commit `feat: add DeviceIdentityStore for persistent pairing identity`

### Task 2: PairingManager persistente
**Files:**
- Modify: `app/src/main/java/app/remotelink/security/PairingManager.kt`
**Interfaces:**
- Consumes: DeviceIdentityStore
- Produces: mesma API verify/verifyStrong/issueSessionToken, mas carrega de store se presente
- [ ] Step 1: Adicionar load/store sem mudar HMAC/SAS/lockout/TTL
- [ ] Step 2: Validar por leitura (nenhuma mudança em challenge `remotelink-pair-v1` / `remotelink-sas-v1`)
- [ ] Step 3: Commit `feat: persist pairing identity without weakening HMAC-SAS`

### Task 3: WebRtcHost modo internet
**Files:**
- Modify: `app/src/main/java/app/remotelink/webrtc/WebRtcHost.kt:211-215`
**Interfaces:**
- Consumes: List<PeerConnection.IceServer> + boolean allowTcpRelay
- Produces: createAnswer(sessionHash, offerSdp, iceServers, allowTcpRelay)
- [ ] Step 1: Manter default `RTCConfiguration(emptyList())` + TCP DISABLED para LAN; adicionar overload com IceServers + TCP ENABLED só em modo internet
- [ ] Step 2: Validar por leitura (DTLS/SRTP preservado, DataChannel auth intacto)
- [ ] Step 3: Commit `feat: add internet ICE mode with TURN fallback, LAN default unchanged`

### Task 4: LanPolicy allowRelay
**Files:**
- Modify: `app/src/main/java/app/remotelink/network/LanPolicy.kt`
**Interfaces:**
- Consumes: boolean allowRelay
- Produces: normalizeRemoteIceCandidate(..., allowRelay=false default), isAllowedLocalIceCandidate inalterado para LAN
- [ ] Step 1: Adicionar flag, em modo internet aceitar relay/tcp autenticado, LAN continua host/udp same-subnet only
- [ ] Step 2: Validar por leitura
- [ ] Step 3: Commit `feat: add relay-aware ICE filter, LAN strict by default`

### Task 5: InternetSignalingClient
**Files:**
- Create: `app/src/main/java/app/remotelink/network/InternetSignalingClient.kt`
**Interfaces:**
- Consumes: Bearer token, HTTPS baseUrl, OkHttp
- Produces: sendOffer/sendCandidate/pollCandidates/getTurnCredentials (TURN REST: username TTL + HMAC)
- [ ] Step 1: Criar cliente OkHttp com timeouts, validação rigorosa, tamanho máximo, sem log de segredo
- [ ] Step 2: Validar por leitura
- [ ] Step 3: Commit `feat: add HTTPS signaling client for internet mode`

### Task 6: MainActivity modo internet
**Files:**
- Modify: `app/src/main/java/app/remotelink/MainActivity.kt`
**Interfaces:**
- Consumes: DeviceIdentityStore, InternetSignalingClient
- Produces: toggle LAN/Internet, QR remoto, revogação, status P2P/TURN
- [ ] Step 1: Adicionar UI mínima sem quebrar fluxo LAN/MediaProjection/Accessibility
- [ ] Step 2: Validar por leitura
- [ ] Step 3: Commit `feat: add internet mode toggle preserving LAN flow`

### Task 7: Web hosted + TURN cred temporária
**Files:**
- Create: `server/Dockerfile`, `server/Caddyfile`, `server/coturn.conf`
- Create: `web/` adaptado de `app/src/main/assets/web/` (iceServers dinâmicos, HTTPS)
- Modify: `app/src/main/assets/web/app.js` (só adicionar iceServers via config, manter polling)
**Interfaces:**
- Consumes: sessão autenticada
- Produces: TURN REST (username timestamp:deviceId, password HMAC sharedSecret, TTL 1h), Caddy 443→127.0.0.1:42000, coturn 3478/5349/443
- [ ] Step 1: Criar server/ + web/ sem expor além de 443 + TURN
- [ ] Step 2: Validar por leitura
- [ ] Step 3: Commit `feat: add internet server stub with coturn and HTTPS proxy`

### Task 8: Versionamento + Actions + Docs
**Files:**
- Modify: `app/build.gradle.kts` (28, 0.9.13-alpha, okhttp dep), `.github/workflows/android-build.yml` (título, job docker TURN)
- Create: `docs/architecture.md`, `docs/signaling.md`, `docs/security.md`, `docs/limits.md`, `docs/deploy.md`
- [ ] Step 1: Bump versão + workflow + docs (o que servidor vê / não vê)
- [ ] Step 2: Validar por leitura (`node --check` web, gradle sintaxe)
- [ ] Step 3: Commit `chore: bump to v0.9.13-alpha with internet MVP docs`
