# Segurança — RemoteLink v0.5-alpha

## Modelo de segurança atual

RemoteLink foi desenhado para controle remoto **visível, consentido e LAN-only**. A segurança não depende de esconder IP ou porta.

### Rede e exposição

- servidor inicia somente por ação do usuário;
- exige Wi‑Fi com IPv4 privado e se vincula apenas ao IPv4 da interface Wi‑Fi ativa;
- clientes precisam estar na sub-rede real dessa interface;
- não usa UPnP, NAT-PMP, port forwarding, túnel reverso ou exposição automática à Internet;
- porta local aleatória por execução;
- `Host` precisa corresponder exatamente ao IP/porta atual;
- requisições POST exigem `Origin` exato;
- candidatos ICE externos (`srflx`, `relay`, `prflx`) são rejeitados no modo LAN;
- candidato mDNS do Chromium é normalizado somente para o mesmo IPv4 que realizou o pareamento HTTP aprovado;
- candidato literal de outro aparelho da LAN é rejeitado;
- uma nova sessão aprovada revoga a anterior.

### Pareamento e sessão

- código de 6 dígitos gerado por `SecureRandom`;
- validade curta e no máximo 5 tentativas;
- código é consumido após o primeiro acerto;
- aprovação física obrigatória no Android;
- token de sessão aleatório de 256 bits;
- o servidor armazena/usa o hash do token;
- sessão vinculada ao IPv4 do navegador aprovado;
- sessão expira e encerra seu peer WebRTC;
- token precisa ser autenticado novamente dentro do RTCDataChannel antes de liberar comandos;
- comandos possuem sequência monotônica para reduzir replay/duplicação;
- mensagens de controle têm limite de tamanho;
- apenas ações explicitamente implementadas são aceitas: toque, swipe, voltar, início, recentes e texto.

### Captura e controle Android

- MediaProjection usa foreground service visível;
- a notificação oferece revogação imediata da transmissão;
- parar o acesso local derruba servidor, sessão, peer e captura;
- AccessibilityService depende de ativação manual pelo usuário nas configurações Android;
- não há shell remoto, execução arbitrária, instalação de APK, leitura de arquivos, câmera, microfone ou clipboard remoto nesta versão;
- não há tentativa de capturar telas protegidas por `FLAG_SECURE`.

### Servidor HTTP local

- limites de body, headers, SDP e candidato ICE;
- timeout de socket e limite de conexões simultâneas;
- `Cache-Control: no-store`, `Pragma: no-cache`, CSP restritiva, `X-Frame-Options: DENY`, `nosniff`, COOP/CORP e política de permissões;
- assets são embarcados no APK; não há JavaScript, fonte ou biblioteca web carregada de CDN.

## Limitação crítica ainda existente

A página abre em `http://IP:PORTA`. O bootstrap de pareamento, token e signaling SDP/ICE ainda trafega por HTTP local.

Isso significa que a v0.5 **não pode ser considerada resistente a um invasor ativo com capacidade de MITM/ARP spoofing na mesma Wi‑Fi**. O WebRTC protege mídia e DataChannel após a negociação, mas o bootstrap ainda precisa de autenticação criptográfica independente da confiança na LAN.

Portanto:

- use a alpha em rede que você controla/confia;
- não abra a porta no roteador;
- não exponha o servidor à Internet;
- em redes públicas/compartilhadas, trate a versão atual como desenvolvimento/teste, não como produto final seguro.

## Próximo marco de segurança

Antes de beta/estável, implementar um bootstrap autenticado de alta entropia, preferencialmente com QR contendo segredo/fingerprint efêmero e prova criptográfica entre navegador e Android. Em seguida: testes instrumentados, fuzzing básico do parser HTTP/signaling, análise de dependências e revisão independente de segurança.

Nenhuma versão deve ser descrita como "impossível de hackear". O objetivo é reduzir superfície, aplicar defesa em profundidade e tornar qualquer autorização remota explícita e revogável.
