# Relatório de testes – PTZ Tapo 0.1

## Testes automáticos (rodam em toda compilação no GitHub Actions)

Ficam em `app/src/test`. Não usam câmera nem controle de verdade.

| Arquivo | O que confere |
|---|---|
| `TapoCryptoTest` | Contas do login antigo (prova da câmera, digest, chaves lsk/ivb, Tapo_tag, AES-CBC), AES-CCM, SHA-256-crypt e SPAKE2+ do login novo. **Os valores esperados foram gerados com a biblioteca pytapo** (Python) com as mesmas entradas; o SHA-256-crypt também confere com o `openssl passwd -5`. |
| `TapoClientTest` | Login completo e pedidos contra uma **câmera simulada** nos três tipos de firmware (antigo, TPAP e muito antigo): informações, mover, presets (ler, ir, criar, apagar), luz, novo login automático quando a sessão expira, senha errada sem insistir (no máximo 2 tentativas) |
| `TapoMotorTest` | Comando de Pan/Tilt do controlador vira passos de movimento do tamanho certo; passos seguidos enquanto segura e nenhum depois de soltar; fim do curso não é tratado como erro |
| `PresetSlotsTest` | Botões de preset ligados aos presets da câmera; preset apagado deixa o botão vazio; reconhece o preset novo depois de gravar |
| `ControllerTest`, `MotionTest` | Movimento gradual, zona morta, curva, rampa, trava de diagonal, paradas de segurança (herdados do PTZ Studio) |
| `MappingTest` | Lista pronta (RT = foto, LT = luz, A/B/X/Y = presets 1–4, direcional = Pan/Tilt); funções que a Tapo não tem (zoom, foco, brilho, Home) não aparecem |
| `SettingsCodecTest` | Gravar e ler as configurações, endereço do vídeo com a Conta da câmera |

## O que depende das câmeras reais (não foi testado)

Só dá para confirmar no celular, com as câmeras:

- [x] **Login na C500 (firmware 1.3.5)**: funciona com a Compatibilidade com terceiros ligada no app Tapo (login seguro antigo, senha em SHA-256). Sem a chave, a câmera usa o login TPAP e recusou a senha.
- [ ] **Login** na C310 e na C320WS (ligar a Compatibilidade com terceiros antes). Ajustes › Câmera em uso mostra o modelo, o firmware e o tipo de login.
- [ ] Senha errada: aparece **Login recusado** e o app para de tentar.
- [x] **Vídeo** com a Conta da câmera (C500).
- [ ] **Movimento na C500**: o joystick move para o lado certo (se invertido, usar Ajustes › Inverter). Ajustar o **Tamanho máximo do passo** até ficar bom: rápido o suficiente, mas parando logo depois de soltar.
- [ ] **Presets**: os presets já feitos no app Tapo aparecem nos botões; gravar pelo app (segurar › Gravar) funciona e não bagunça os outros botões.
- [ ] **Luz da C320WS** acende e apaga, e o botão mostra o estado certo. (O app tenta, nesta ordem, os comandos de holofote, refletor e luz branca forçada; Ajustes mostra qual funcionou.)
- [ ] **Foto**: sai com a imagem do vídeo e aparece na galeria em Imagens/PTZ Tapo (também pelo RT do controle e na tela cheia).
- [ ] C310 (sem motor): mover mostra um aviso, sem travar o app.
- [ ] GameSir: analógico move, A/B/X/Y chamam presets, RT tira foto, LT acende a luz.
- [ ] Ao sair do app ou desligar o controle, a câmera para de receber passos.
