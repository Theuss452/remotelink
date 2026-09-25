# facts.md — RemoteLink v0.9.12-alpha (main 31d93cd)

## FACT
- Repo Theuss452/remotelink, branch main, versionCode 27 versionName 0.9.12-alpha, workflow `Build RemoteLink v0.9.12-alpha` com `assembleDirectDebug` + artifact, `node --check` web/*.js. [Explorer A/C + leitura direta]
- Perfil `java-gradle`, lang kotlin, build gradle, adapter `java-gradle.json` (Windows: `gradlew.bat`). Sem Android SDK local garantido — build oficial no GitHub Actions.
- `LocalControlServer.kt`: ServerSocket bind só IPv4 privado Wi-Fi, porta aleatória 42000-52000, endpoints `/api/status`, `/api/pair`, `/api/pair/status`, `/api/webrtc/offer`, `/api/webrtc/candidate`, `/api/webrtc/candidates`, `/api/webrtc/state`, `/api/session/stop`. Auth Bearer hash + IP binding + Host/Origin exatos, body 256KB, SDP 180K chars, sessão única, TTL sessão 30min, pending 90s.
- `PairingManager.kt`: newCode 6 dígitos SecureRandom + pairId 12 bytes + secret 32 bytes b64url, TTL ~5min, MAX_FAILURES 5 → LOCKED. verify SHA-256, verifyStrong HMAC-SHA256 `remotelink-pair-v1|pairId|nonce` (nonce 16-32, proof 32), SAS `remotelink-sas-v1|...` %1M 6 dígitos, issueSessionToken 32 bytes b64url, invalidate zera segredo.
- `WebRtcHost.kt:211-215`: `RTCConfiguration(emptyList())`, `UNIFIED_PLAN`, `GATHER_CONTINUALLY`, `TcpCandidatePolicy.DISABLED`. createAnswer/addRemoteCandidate/drainLocalCandidates, DataChannel control/file com auth sha256 + seq monotônica, watchdog 5s/30s.
- `LanPolicy.kt`: só `host/udp`, rejeita srflx/relay/prflx, normaliza `.local` para IP aprovado, `isSameSubnet` bitwise, `parsePrivateIpv4` site-local.
- `assets/web/`: strong-pair.js QR `#pair=pairId.secret` (pairId 12-80, secret 40-60 b64url), nonce 24 bytes, proof/SAS HMAC, poll `/api/pair/status` → sessionStorage; app.js `RTCPeerConnection({iceServers:[]})`, offer→`/api/webrtc/offer`, candidate→`/api/webrtc/candidate`, poll 300ms candidates + 700ms state.
- `MainActivity.kt`: start/stop server, MediaProjection REQUEST_CAPTURE (Android 14+ MediaProjectionConfig), QR render, aprovação física, revokeSessionsFromDevice, Accessibility disclosure, UpdateManager. Footer `LAN-only • QR/HMAC/SAS • uma sessão por vez`.
- `ScreenCaptureService` foreground visível + `ScreenCapturerAndroid`; `RemoteAccessibilityService` tap/swipe/drag/multitouch/pinch/teclado/back/home/recentes, ativação manual.
- `IncomingFileReceiver`: gate `SessionCapabilities.files=false` default, MAX_FILE 100MB, chunk 64KB, aprovação Android. Comandos whitelist TOUCH/KEYBOARD.
- CSP restritiva + `X-Frame-Options: DENY`, `nosniff`, COOP/CORP, `Permissions-Policy` câmera/mic off, assets embarcados sem CDN.
- Ausência confirmada: sem backend cloud, sem WebSocket remoto, sem STUN/TURN, sem DB, sem login/conta, sem coturn.conf/Dockerfile/server/.
- Docs drift: README v0.2 / SECURITY v0.5 / PROJECT_STATUS v0.2 vs código v0.9.12.

## INFERENCE
- Design LAN-only com IP-bound auth + ICE binding; sessão única; lockout 5 tentativas; bootstrap HTTP local é limitação crítica MITM (admitida em SECURITY.md); WebRTC DTLS/SRTP pós-negociação.
- `iceTransportPolicy:'all'` no browser vs rejeição TCP no Android — mismatch que precisará de modo internet.
- Para internet sem quebrar LAN: manter `LocalControlServer` intacto, adicionar `InternetSignalingClient` + `DeviceIdentityStore` + `server/` novo + coturn separado + web hosted HTTPS.

## UNKNOWN
- Descoberta de porta (QR mostra IP:porta? status endpoint?); SessionCapabilities remota vs UI local; ICE em Android 14+ real; QR geração Android exata; DTLS/SRTP config fina; rede trocando mid-session; concorrência pairing.
- Explorer B (platform/build) retornou garbage de modelo — coberto por leitura direta acima; retry único permitido mas não bloqueia planner.

## Arquivos-alvo (sem quebrar LAN)
- Modificar: `WebRtcHost.kt`, `LanPolicy.kt` (adicionar modo), `PairingManager.kt` (identidade persistente), `MainActivity.kt`, `assets/web/app.js` + hosted, `app/build.gradle.kts` (v28), `.github/workflows/android-build.yml`, docs.
- Criar: `network/InternetSignalingClient.kt`, `auth/DeviceIdentityStore.kt`, `server/` + Dockerfile + coturn.conf + reverse proxy, `docs/` arquitetura.
- Não mexer: `ScreenCaptureService`, `RemoteAccessibilityService` (só doc reconsentimento).
