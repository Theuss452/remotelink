# RemoteLink — Release assinado e atualizações

## Objetivo

O RemoteLink possui dois canais de distribuição:

- `directRelease`: APK assinado para instalação direta. Pode verificar e baixar atualizações por HTTPS, mas o Android sempre mantém a confirmação final de instalação.
- `playRelease`: AAB destinado ao Google Play/Internal Testing. Não declara `REQUEST_INSTALL_PACKAGES`; atualizações são gerenciadas pelo Google Play.

Não tente contornar Play Protect ou o instalador do Android. O canal direto foi projetado para trabalhar com as proteções da plataforma.

## 1. Criar a chave de release uma única vez

No Windows com JDK 17 instalado:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\create-release-keystore.ps1
```

Guarde `tools/private-signing/remotelink-release.jks` e as senhas fora do repositório, com pelo menos um backup offline seguro. O `.gitignore` bloqueia extensões comuns de chaves, mas isso não substitui cuidado operacional.

Nunca gere uma chave nova para cada versão. Android exige a mesma identidade de assinatura para atualizações do mesmo pacote.

## 2. Configurar GitHub Actions Secrets

Em `Settings > Secrets and variables > Actions`, crie Repository secrets:

- `ANDROID_KEYSTORE_BASE64`: Base64 do arquivo `.jks`.
- `ANDROID_KEYSTORE_PASSWORD`: senha do keystore.
- `ANDROID_KEY_ALIAS`: normalmente `remotelink`.
- `ANDROID_KEY_PASSWORD`: senha da chave/alias.

Para copiar o JKS como Base64 no PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('.\tools\private-signing\remotelink-release.jks')) | Set-Clipboard
```

Não coloque esses valores em arquivos do repositório, issues, logs ou código do app.

## 3. Canal de atualização direta

O app nunca deve receber token do GitHub ou outra credencial privada. Como o repositório de código pode ser privado, hospede somente os artefatos de distribuição em um endpoint HTTPS acessível sem segredo do usuário, por exemplo:

- uma página/servidor HTTPS de releases;
- um bucket de objetos somente-leitura;
- um repositório separado de distribuição pública;
- outro CDN HTTPS controlado pelo projeto.

Configure Repository variables:

- `REMOTELINK_UPDATE_MANIFEST_URL`: URL HTTPS do `updates.json`.
- `REMOTELINK_DIRECT_APK_URL`: URL HTTPS da APK release.

O workflow gera um `updates.json` semelhante a:

```json
{
  "packageName": "app.remotelink",
  "versionCode": 10,
  "versionName": "0.7-alpha",
  "apkUrl": "https://downloads.example/RemoteLink-v0.7-alpha-release.apk",
  "sha256": "...",
  "notes": "Notas da versão"
}
```

## 4. Verificações feitas pelo app antes de instalar

O atualizador direto exige:

1. manifesto e APK por HTTPS;
2. `packageName` exatamente `app.remotelink`;
3. `versionCode` maior que a versão instalada;
4. SHA-256 da APK igual ao publicado no manifesto;
5. APK Android válida;
6. certificado de assinatura da APK exatamente igual ao certificado do RemoteLink instalado.

Só depois dessas verificações o app abre o instalador oficial do Android.

O RemoteLink não instala silenciosamente e não desativa Play Protect.

## 5. Migração do debug atual

Os APKs `debug` são assinados com chave de depuração. A primeira passagem para `directRelease` assinado provavelmente exigirá:

1. parar qualquer sessão RemoteLink;
2. desinstalar a versão debug;
3. instalar o primeiro `RemoteLink-vX-release.apk` assinado;
4. manter a mesma chave release para todas as versões futuras.

Depois disso, futuras releases diretas podem ser instaladas por cima da anterior.

## 6. Google Play

O workflow também produz `RemoteLink-vX-play.aab` quando os Secrets de assinatura estão configurados.

Para publicar um app que usa `AccessibilityService` para controle remoto e não é uma ferramenta destinada a pessoas com deficiência:

- não declare `isAccessibilityTool=true`;
- mantenha divulgação destacada no app e consentimento afirmativo antes de direcionar o usuário à ativação;
- preencha a declaração de AccessibilityService no Play Console;
- documente claramente o uso na listagem e forneça os materiais de revisão solicitados pelo Google Play.

O flavor `play` não inclui o instalador de APKs do canal direto.

## 7. Play Protect

Assinar uma APK é obrigatório para distribuição profissional, mas não garante que o Play Protect nunca mostre aviso em sideload. Apps fora da Play Store, especialmente com APIs sensíveis como AccessibilityService e captura de tela, podem ser verificados ou bloqueados conforme políticas e reputação.

Para minimizar avisos e obter atualizações mais automáticas, o canal preferido é Google Play Internal/Closed Testing e, depois, produção quando o app cumprir os requisitos da loja.
