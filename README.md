# PTZ Tapo

App Android para comandar câmeras **TP-Link Tapo** (testado no projeto para C500, C310 e C320WS) pelo celular,
com o joystick da tela ou um controle **GameSir Nova 2 Lite**. É um app separado do PTZ Studio Android.

## O que faz

- **Vídeo ao vivo** da câmera (RTSP, alta ou baixa qualidade).
- **Mover a câmera** (C500 e outras com motor) com o joystick da tela, as 8 setas ou o controle físico.
- **Presets**: 8 botões ligados aos presets guardados na câmera. Toque = vai ao preset; segure = renomear ou gravar a posição atual.
- **Foto**: tira a foto do vídeo ao vivo e guarda na galeria (Imagens/PTZ Tapo).
- **Luz**: acende e apaga a luz da câmera (C320WS e outras com holofote).
- Controle físico com botões configuráveis (padrão: RT = foto, LT = luz, A/B/X/Y = presets 1 a 4).

Em breve: lista das gravações do cartão de memória por dia, para assistir e baixar para o celular.

## Como instalar

Baixe o arquivo `.apk` da versão mais nova na página **Releases** deste repositório, pelo próprio celular, e abra para instalar.
Precisa de Android 10 ou mais novo.

## O que cadastrar para cada câmera

| Campo | Para que serve | Onde achar |
|---|---|---|
| Endereço IP | Achar a câmera na rede | App Tapo › câmera › engrenagem › Informações do dispositivo (ou no roteador) |
| Senha da conta Tapo | Mover, presets e luz | A mesma senha usada para entrar no app Tapo (conta da dona da câmera) |
| Conta da câmera (usuário e senha) | Só o vídeo ao vivo | Criar no app Tapo › câmera › engrenagem › Configurações avançadas › Conta da câmera |

**Importante:** no app Tapo, ligue **Eu › Serviços de terceiros › Compatibilidade com terceiros**
(*Third-Party Compatibility*). Sem isso, câmeras com firmware novo (ex.: C500 1.3.5) recusam o login de outros
apps mesmo com a senha certa. Confirmado na C500 1.3.5: com a chave ligada ela passa a usar o login seguro antigo.

A senha da conta Tapo fica guardada só no celular, cifrada com a chave do próprio aparelho. Depois de várias senhas
erradas a câmera bloqueia o login por um tempo; por isso o app para de tentar quando a senha é recusada
(corrija em Câmeras › Editar e toque em Ajustes › Conectar de novo).

## Como funciona por dentro

- O comando da câmera é o mesmo protocolo local que o app Tapo usa (HTTPS na porta 443), portado da biblioteca
  [pytapo](https://github.com/JurajNyiri/pytapo): login antigo (*encrypt_type 3*, AES-CBC) e login novo
  (*TPAP*: SPAKE2+ com AES-CCM), escolhido sozinho conforme o firmware.
- As contas de criptografia são conferidas nos testes com valores gerados pela própria pytapo, e o login completo
  dos dois tipos é testado contra uma câmera simulada. **Isso não substitui o teste na câmera de verdade** — veja `TESTES.md`.
- A Tapo não tem "velocidade" de movimento: o app manda passos seguidos enquanto o joystick está inclinado, do
  tamanho proporcional à inclinação e à velocidade escolhida (ajuste o tamanho máximo do passo em Ajustes).

## Compilar

O GitHub Actions compila e testa a cada envio para `main` e publica o APK em Releases.
Localmente: `./gradlew testDebugUnitTest assembleDebug` (JDK 17).
