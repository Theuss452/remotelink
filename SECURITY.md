# Segurança — RemoteLink v0.2-alpha

## Implementado

- servidor só inicia por ação do usuário;
- exige Wi‑Fi com IPv4 privado (RFC1918/site-local) e se vincula somente a esse endereço;
- clientes precisam pertencer à sub-rede Wi‑Fi ativa;
- não configura UPnP, NAT-PMP ou port forwarding;
- porta aleatória por execução;
- código de 6 dígitos temporário com `SecureRandom`;
- até 5 tentativas antes de invalidar o pareamento;
- código é consumido no primeiro acerto;
- aprovação física no Android;
- token de sessão aleatório de 256 bits, validado por hash e preso ao IP aprovado;
- uma sessão WebRTC por vez;
- signaling ICE aceita apenas candidatos `host` da LAN atual (ou host mDNS do navegador), sem STUN/TURN;
- RTCDataChannel precisa autenticar o token antes de comandos;
- sequência monotônica nos comandos para rejeitar duplicatas/replay triviais;
- POST exige `Origin` exato e toda requisição exige `Host` exato;
- limites de body/header/SDP/ICE, timeouts e limite de conexões concorrentes;
- CSP, `X-Frame-Options`, `no-store` e headers defensivos;
- AccessibilityService protegido por `BIND_ACCESSIBILITY_SERVICE`;
- captura usa foreground service visível;
- encerramento explícito da sessão revoga a captura.

## Limitação crítica

A UI abre em `http://IP:PORTA`. Pareamento, token e SDP/ICE ainda trafegam nesse signaling HTTP local. As barreiras acima reduzem a superfície, mas **não substituem um canal autenticado e criptografado contra um invasor ativo na mesma Wi‑Fi**.

Use esta alpha apenas para desenvolvimento/testes em rede confiável. Não exponha a porta à Internet e não configure port forwarding.

## Próximo marco de segurança

Antes de considerar o projeto profissional, o bootstrap/signaling deve ganhar autenticação criptográfica independente da confiança na LAN — por exemplo, identidade/fingerprint efêmera entregue pelo QR e canal TLS/autenticado — e o projeto deve passar por testes em dispositivos reais e revisão de segurança.
