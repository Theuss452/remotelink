# Limites — Internet MVP (espelhos da LAN)

| Limite | Valor | Onde |
|---|---|---|
| Corpo HTTP (req/resp) | 256KB (262144) | `LocalControlServer` / `InternetSignalingClient` |
| SDP offer/answer | 180K chars | idem |
| Candidate único | 8KB | `InternetSignalingClient` |
| Poll de candidates | máx 64 | idem |
| Pareamento | TTL ~5min, 6 dígitos, lockout 5 | `PairingManager` (intacto) |
| Sessão | TTL 30min, única | servidor (intacto) |
| Pending pairing | 90s | servidor (intacto) |
| Credencial TURN | TTL 3600s | `coturn.conf` + `getTurnCredentials` |
| Stale nonce TURN | 600s | `coturn.conf` |
| Timeouts signaling | 10s connect / 15s read-write | `InternetSignalingClient` |
| Polling web | 300ms candidates / 700ms state | `app.js` (inalterado) |
| URIs TURN por resposta | máx 8, só `turn:`/`turns:` | `InternetSignalingClient` |
| DeviceIdentityStore | pairId 12–80 chars, segredo 40–60 b64url | `DeviceIdentityStore` |

Ultrapassar qualquer limite → rejeição silenciosa (sem detalhe em erro/log).
