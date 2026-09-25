# syntax=docker/dockerfile:1
# RemoteLink Internet MVP — proxy HTTPS (Caddy) + relay TURN (coturn).
# O celular NUNCA expõe porta: só faz HTTPS de saída para este servidor.
# Segredos (TURN static-auth-secret, domínio) entram via ENV no runtime,
# nunca hardcoded na imagem.
FROM alpine:3.20
RUN apk add --no-cache caddy coturn ca-certificates \
  && adduser -D -H -s /sbin/nologin turnserver
COPY Caddyfile /etc/caddy/Caddyfile
COPY coturn.conf /etc/turnserver.conf
# O backend de signaling (mesmo contrato autenticado da LAN) escuta em
# 127.0.0.1:42000 dentro do host/rede do servidor — ver docs/deploy.md.
EXPOSE 443/tcp 3478/tcp 3478/udp 5349/tcp
CMD ["sh", "-c", "turnserver -c /etc/turnserver.conf --static-auth-secret \"$TURN_SECRET\" & caddy run --config /etc/caddy/Caddyfile --adapter caddyfile"]
