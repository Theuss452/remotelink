# Task — RemoteLink Internet Access

Origem: C:\Users\matheus\Downloads\remotelink\Instruçoesremotelink.txt (881 linhas) + mensagens do usuário em 2026-09-25.

Tarefa literal:
- Continuar desenvolvimento do RemoteLink (Android controle remoto Android → navegador, hoje LAN-only) para funcionar pela Internet.
- Requisitos: servidor de sinalização + TURN coturn fallback (P2P > TURN/UDP > TURN/TCP > TURN/TLS 443), Chromebook só navegador HTTPS sem app/extensão/VPN/Tailscale, preservar pairing forte (QR/HMAC-SHA256/SAS 6 dígitos/aprovação física/token sessão/capabilities/sessão única), preservar WebRTC (MediaProjection/DataChannel/AccessibilityService), HTTPS, credenciais TURN temporárias, identidade conta→dispositivo→sessão, pairing remoto com confirmação Android, respeitar MediaProjection/Accessibility (sem bypass), não quebrar vídeo/controle/segurança/arquivos, performance LAN + adaptação internet, métricas P2P/TURN, compat VPN/proxy/CGNAT, servidor simples + banco adequado, segurança operacional, testes c1-8 + segurança, git push direto origin/main, versionamento, GitHub Actions assemble + artifact + título versão, documentação arquitetura/limites/deploy.
- Fluxo autorizado: push direto na main, repo público Theuss452/remotelink, gh já autenticado, build via GitHub Actions (sem compilar local por falta de espaço).
- Etapa atual: iniciar mudanças agora (validação já entregue, sem código modificado até aqui).
