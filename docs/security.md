# Segurança — Internet MVP

## O que o servidor VÊ

- **Signaling (backend + Caddy):** metadados — SDP (codecs, IPs candidatos),
  timestamps, tamanho das mensagens, IP de origem. Necessário para estabelecer
  a sessão; minimizado pelos mesmos limites da LAN (256KB/180K/64 por poll).
- **TURN (coturn):** IPs, portas e volume de pacotes relayados. **Relay cego:**
  a mídia é DTLS/SRTP fim-a-fim com chaves negociadas só entre celular e
  browser — o TURN não tem as chaves e não decifra conteúdo.
- **TLS:** Caddy termina o TLS do browser; dentro do host, proxy para
  `127.0.0.1:42000` (loopback, sem exposição).

## O que o servidor NÃO vê

- Conteúdo da tela, toques, teclado, arquivos (tudo via DataChannel/SRTP
  autenticado fim-a-fim).
- Segredo de pareamento (só HMAC/SAS transitam no pareamento, nunca o segredo).
- Token de sessão em log (proibido por contrato do cliente e do backend).
- O `static-auth-secret` do TURN (só ENV no runtime, nunca no repo/logs).

## Garantias herdadas da LAN (inalteradas)

- Sem porta pública no celular; sem senha fixa; sem token fixo em JS; TURN
  sempre com auth (`use-auth-secret` + `stale-nonce`); sem CORS aberto (origem
  única); sem bypass de MediaProjection/Acessibilidade; sem cripto própria;
  sem MJPEG.
- Sessão única, lockout após 5 falhas, TTLs 5min/30min/90s, aprovação física
  com SAS comparável, revogação imediata (também limpa o `DeviceIdentityStore`).

## Riscos residuais e mitigação

- SDP via servidor revela IPs candidatos → mitigado por ser sessão autenticada
  + retenção mínima recomendada (ver `deploy.md`).
- Roubo de credencial TURN (válida ~1h, escopo só relay) → TTL curto +
  `denied-peer-ip` bloqueando ranges internos/abusivos.
- `DeviceIdentityStore` usa SharedPreferences privado (sandbox do app), não
  EncryptedSharedPreferences → evolução planejada sem mudar a interface; o
  segredo continua com TTL de 5min e é zerado ao invalidar.
