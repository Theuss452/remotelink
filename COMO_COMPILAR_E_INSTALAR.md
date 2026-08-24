# Compilar e instalar — RemoteLink v0.2-alpha

## Windows — jeito mais fácil

1. Instale o Android Studio.
2. No SDK Manager, instale **Android 16 / API 36**.
3. Extraia este projeto para uma pasta simples, por exemplo `C:\Projetos\RemoteLink-v0.2-alpha`.
4. Execute `PREPARAR_E_COMPILAR.bat`.
5. Na primeira compilação, deixe a Internet ativa para baixar Gradle e dependências Maven.
6. Ao concluir, procure `RemoteLink-v0.2-alpha-debug.apk` na raiz do projeto.

O APK original também fica em `app\build\outputs\apk\debug\app-debug.apk`.

## Android Studio

Depois que o projeto estiver preparado, você também pode abrir a pasta no Android Studio e usar **Build > Generate Bundle(s) / APK(s) > Generate APK(s)**. Para testar diretamente em um celular conectado por USB, selecione o aparelho e use **Run**.

## Instalar no Android

Você pode usar o Android Studio/ADB ou transferir o APK debug para o celular e abri-lo. Se instalar manualmente, o Android poderá solicitar autorização para instalar apps dessa fonte.

## Primeira execução

1. Abra o RemoteLink.
2. Ative a permissão de acessibilidade do RemoteLink.
3. Autorize a captura de tela; para controle remoto, compartilhe a tela inteira.
4. Toque em **Iniciar acesso local**.
5. Abra no Chromebook/PC o endereço exibido pelo celular.
6. Digite o código e aprove a solicitação no Android.

## Requisitos desta versão

- JDK 17 ou superior compatível com o build;
- Android SDK/API 36;
- Android Gradle Plugin 8.13.2;
- Gradle 8.13;
- Internet na primeira compilação.

Se a rede escolar bloquear comunicação entre clientes, o endereço local não abrirá mesmo com ambos no mesmo Wi‑Fi. Isso é uma política da rede, não um erro necessariamente no app.
