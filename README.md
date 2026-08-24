# RemoteLink v0.2-alpha

Protótipo LAN-only de controle remoto **Android → navegador**. O APK roda no celular controlado; Chromebook/PC/tablet usa somente o navegador na mesma rede Wi‑Fi.

## v0.2

- painel web redesenhado;
- vídeo da tela por WebRTC;
- comandos por RTCDataChannel autenticado;
- mouse → toque, arraste → swipe e roda → rolagem;
- Voltar, Início e Recentes;
- envio de texto para o campo focado;
- latência, FPS, resolução e bitrate;
- tela cheia e Ajustar/Preencher;
- código temporário + confirmação física no Android;
- token preso ao IP aprovado;
- servidor vinculado somente ao IPv4 privado da Wi‑Fi e filtro pela sub-rede real;
- sem STUN/TURN, UPnP ou port forwarding nesta versão.

## Segurança

Vídeo e comandos usam WebRTC, mas o bootstrap/signaling ainda ocorre em HTTP local. Portanto **v0.2-alpha não é uma versão final para Wi‑Fi hostil/público**. Veja `SECURITY.md`.

## Compilar

No Windows, instale Android Studio + Android SDK API 36 e execute `PREPARAR_E_COMPILAR.bat`. O script prepara o Gradle Wrapper, verifica o hash do wrapper JAR e executa `assembleDebug`.

Se o build terminar, o APK será copiado para a raiz como `RemoteLink-v0.2-alpha-debug.apk`. Instruções completas: `COMO_COMPILAR_E_INSTALAR.md`.

## Uso

1. Instale e abra o APK no Android.
2. Ative o serviço de acessibilidade se quiser permitir controle.
3. Autorize a transmissão da tela e escolha compartilhar a tela inteira quando o Android oferecer essa opção.
4. Toque em **Iniciar acesso local**.
5. No Chromebook/PC na mesma Wi‑Fi, abra o endereço mostrado, por exemplo `http://192.168.1.37:48120`.
6. Digite o código de 6 dígitos e aprove a solicitação no Android.
7. O navegador negocia o WebRTC e mostra a tela.

Redes escolares podem usar client/AP isolation e impedir comunicação direta entre dispositivos. O RemoteLink não tenta contornar políticas da rede ou do ChromeOS.

## Estado

A estrutura, JavaScript e XML deste pacote foram validados estaticamente. O APK não foi compilado neste ambiente porque ele não possui Android SDK; o primeiro build/teste em aparelho físico ainda é necessário.
