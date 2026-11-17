# Levanta

Despertador de disciplina para Android. Não tem botão de cancelar: o alarme só para quando a missão é cumprida, e volta a tocar se você não confirmar que continua de pé.

Feito para ficar num tablet **longe da cama**, no carregador.

## Como funciona

1. **Toca na hora.** Acende a tela por cima do bloqueio, no volume máximo. Os botões de volume do aparelho não fazem nada enquanto ele toca.
2. **Missão para desligar.** Uma ou mais, na ordem:
   - **Código em outro cômodo:** ler com a câmera um código de barras ou QR code que você cadastrou (a pasta de dente no banheiro, por exemplo).
   - **Contas:** resolver contas num teclado próprio na tela.
   - **Frase:** montar sua frase de compromisso tocando nas palavras na ordem certa.
3. **Trégua.** Enquanto você está resolvendo, o som fica baixo. Parou de mexer por 30 segundos, o volume volta. O teto é de 3 minutos por missão.
4. **Checagem.** Minutos depois, o tablet pergunta "você continua de pé?". Sem resposta em 45 segundos, é recaída: o alarme volta e a missão recomeça.
5. **Fim.** Passou pela checagem, o despertar entra no histórico e, se você quiser, o app do Claude abre em seguida.

Se ninguém atender em 20 minutos, o alarme desiste e registra "sem resposta" no histórico. Reiniciar o tablet no meio não adianta: ao ligar de novo, o alarme retoma.

Tudo é configurável em **Ajustes**: quais missões, quantas contas e a dificuldade, a frase, número de checagens e intervalo, sonecas permitidas (o padrão é nenhuma) e volume crescente.

## Instalar no tablet

1. No tablet, abra a página de versões do repositório: https://github.com/iacomthiago/levanta-despertador/releases/latest
2. Baixe o arquivo **levanta.apk** e toque nele.
3. O Android vai avisar que o navegador não pode instalar apps. Toque em **Configurações**, ligue **Permitir desta fonte** e volte.
4. Toque em **Instalar**. Se o Play Protect perguntar, escolha **Instalar mesmo assim** (o app não está na loja, por isso o aviso).

Para atualizar, repita os passos com a versão nova: ela instala por cima e mantém alarmes e histórico.

## Preparar o tablet (uma vez)

Ao abrir o app, um cartão amarelo lista o que falta liberar. Toque em **Liberar** em cada item:

- **Notificações** e **tela cheia:** é o que deixa o alarme abrir sozinho com o tablet bloqueado.
- **Economia de bateria:** escolha "Permitir". Sem isso a Samsung pode encerrar o app durante a noite.
- **Aparecer sobre outros apps** (recomendado): faz o alarme tomar a tela mesmo se você estiver usando o tablet.

Em tablets Samsung, confira também em **Configurações > Bateria > Limites de uso em segundo plano** que o Levanta não está em "Apps em suspensão" nem em "Apps em suspensão profunda".

Depois:

1. Crie um alarme e toque em **Testar agora**. Bloqueie a tela e veja o alarme abrir sozinho. No teste, a checagem chega em 20 segundos e nada entra no histórico.
2. Deixe o volume de **alarme** do tablet alto e o tablet **no carregador** durante a noite.
3. Coloque o tablet longe da cama.

## Limites

- Desligar o tablet, forçar a parada do app nas configurações ou desinstalar silencia o alarme. Nenhum app consegue impedir isso.
- Se o tablet ficar sem bateria, não toca. Por isso o carregador.
- Quando você dormir fora de casa, use o alarme normal do celular.

## Desenvolvimento

O projeto é Kotlin com Jetpack Compose, sem dependência de serviços do Google.

- `app/src/main/java/com/iacomthiago/levanta/`
  - `Data.kt`: alarmes, ajustes, despertar em andamento e histórico (SharedPreferences).
  - `Scheduler.kt`: agendamento no AlarmManager e os receptores de alarme e de reinício.
  - `AlarmService.kt`: som, volume e a máquina de fases do despertar.
  - `AlarmActivity.kt`: a tela que abre por cima do bloqueio.
  - `Missions.kt`: contas, frase e leitura de código.
  - `MainActivity.kt`, `Setup.kt`, `Ui.kt`: telas do app, permissões e componentes.
- `.github/workflows/build.yml`: a cada envio para `main`, o GitHub compila o APK, roda `ci/smoke.py` num emulador (alarme de ponta a ponta, com capturas de tela) e, se passar, publica uma versão nova.
- `ci/make_sounds.py`: gera os sons do alarme e da checagem.

Não é preciso ter o Android SDK no computador: a compilação acontece no GitHub. A chave de assinatura fica nos segredos do repositório (`KEYSTORE_B64` e `KEYSTORE_PASSWORD`).
