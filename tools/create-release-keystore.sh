#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-tools/private-signing}"
KEYSTORE="$OUT_DIR/remotelink-release.jks"
ALIAS="${REMOTELINK_KEY_ALIAS:-remotelink}"

mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

if [[ -e "$KEYSTORE" ]]; then
  echo "Erro: $KEYSTORE já existe. Não sobrescreva a chave de assinatura permanente." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "Erro: keytool não encontrado. Instale/use JDK 17+." >&2
  exit 1
fi

read -rsp "Senha forte para o keystore: " STORE_PASS; echo
if [[ ${#STORE_PASS} -lt 12 ]]; then
  echo "Use uma senha com pelo menos 12 caracteres." >&2
  exit 1
fi
read -rsp "Repita a senha: " STORE_PASS_2; echo
if [[ "$STORE_PASS" != "$STORE_PASS_2" ]]; then
  echo "As senhas não conferem." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STORE_PASS" \
  -keypass "$STORE_PASS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=RemoteLink, OU=Release, O=RemoteLink, L=Unknown, ST=Unknown, C=BR"

chmod 600 "$KEYSTORE"
B64="$OUT_DIR/remotelink-release.jks.b64"
base64 -w 0 "$KEYSTORE" > "$B64" 2>/dev/null || base64 "$KEYSTORE" | tr -d '\n' > "$B64"
chmod 600 "$B64"

cat <<EOF

Chave criada com sucesso:
  $KEYSTORE

Base64 para GitHub Actions Secret:
  $B64

Alias:
  $ALIAS

Cadastre no GitHub:
  ANDROID_KEYSTORE_BASE64   = conteúdo de $B64
  ANDROID_KEYSTORE_PASSWORD = a senha digitada
  ANDROID_KEY_ALIAS         = $ALIAS
  ANDROID_KEY_PASSWORD      = a mesma senha

IMPORTANTE:
1. Baixe $KEYSTORE para um backup fora do Codespace.
2. Não faça commit do arquivo .jks nem do .b64.
3. Não gere outra chave para versões futuras do mesmo canal direto.
EOF
