# Deploy — servidor Internet MVP

## Pré-requisitos

- Host Linux com Docker, domínio próprio com DNS apontando para o host.
- Segredos gerados fora do repo: `TURN_SECRET` (≥32 bytes aleatórios),
  `DOMAIN` (domínio canônico), `TLS_EMAIL` (para o Caddy).

## Subir

```sh
cp app/src/main/assets/web/* /srv/web/   # ou volume montado
cp web/config.js /srv/web/config.js
TURN_SECRET="$(openssl rand -base64 32)" DOMAIN=remotelink.example.com \
TLS_EMAIL=admin@example.com docker build -f server/Dockerfile -t remotelink-internet-mvp server/
docker run -d --name remotelink-internet -p 443:443 -p 3478:3478 \
  -p 3478:3478/udp -p 5349:5349 -e TURN_SECRET -e DOMAIN -e TLS_EMAIL \
  -v /srv/web:/srv/web:ro remotelink-internet-mvp
```

## Backend de signaling (`127.0.0.1:42000`)

O stub (`server/`) faz proxy `/api/*` para um backend **a implementar** no
host, que DEVE replicar o contrato autenticado da LAN (`LocalControlServer`):
Bearer + sessão única + TTLs + limites + aprovação física via push ao celular
(o celular mantém HTTPS de saída; nunca recebe conexão). Sem esse backend, o
Caddy serve só os estáticos e `/api/*` retorna 502 — comportamento seguro por
padrão.

## App Android

Build com `REMOTELINK_INTERNET_URL=https://<DOMAIN>` (vira
`BuildConfig.INTERNET_SERVER_URL`). Sem a variável, o toggle de internet mostra
"Servidor não configurado" e a LAN segue normal.

## Operação

- Portas públicas: **só** 443/tcp (Caddy) + TURN 3478/5349. Nada além disso.
- Rotação de `TURN_SECRET`: gere novo, reinicie o container; credenciais
  antigas expiram em ≤1h.
- Logs: nunca ative log de corpo/URI com query em produção; sem `turnadmin`
  remoto (`no-cli`).
- `ScreenCaptureService` / `RemoteAccessibilityService`: sem mudança — o
  reconsentimento no Android continua manual e documentado na tela principal.
