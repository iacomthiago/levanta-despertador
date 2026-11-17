# Levanta

Despertador de disciplina para Android. Não tem botão de cancelar: o alarme só para quando a missão é cumprida, e volta a tocar se você não confirmar que continua de pé.

Feito para ficar num tablet **longe da cama**, no carregador.

## Como funciona

1. **Toca na hora.** Acende a tela por cima do bloqueio, no volume máximo. Começa com a abertura da série HOTARU; se a missão não for cumprida em 60 segundos, a música vira um bipe insistente. Os botões de volume do aparelho não fazem nada enquanto ele toca.
2. **Missão para desligar.** Uma ou mais, na ordem:
   - **Código em outro cômodo:** ler com a câmera um código de barras ou QR code que você cadastrou (a pasta de dente no banheiro, por exemplo).
   - **Questões do concurso:** afirmações de certo ou errado sobre Constituição e Licitações, até somar o número de acertos pedido. Errou, lê a explicação e responde outra. Primeiro aparecem as que você errou e as que ainda não viu.
   - **Contas:** resolver contas num teclado próprio na tela.
   - **Frase:** montar sua frase de compromisso tocando nas palavras na ordem certa.
   - **Missão surpresa:** uma extra, sorteada a cada despertar. Ou falar sua afirmação em voz alta para o tablet ouvir, ou repetir uma sequência de quadros que acendem.
3. **Trégua.** Enquanto você está resolvendo, o som fica baixo. Parou de mexer por 30 segundos, o volume volta. O teto é de 3 minutos por missão.
4. **Checagem.** Minutos depois, o tablet pergunta "você continua de pé?". Sem resposta em 45 segundos, é recaída: o alarme volta e a missão recomeça.
5. **Fim.** Passou pela checagem, o despertar entra no histórico, a tela de bom dia mostra quantos dias faltam para a prova e, se você quiser, o app do Claude abre em seguida.

Se ninguém atender em 20 minutos, o alarme desiste e registra "sem resposta" no histórico. Reiniciar o tablet no meio não adianta: ao ligar de novo, o alarme retoma.

Tudo é configurável em **Ajustes**: quais missões, quantos acertos e quantas contas, a frase, a afirmação, a data da prova, número de checagens e intervalo, sonecas permitidas (o padrão é nenhuma), música de abertura e volume crescente. Lá também dá para treinar as duas missões surpresa fora do alarme.

## Instalar no tablet

1. No tablet, abra a página de versões do repositório: https://github.com/iacomthiago/levanta-despertador/releases/latest
2. Baixe o arquivo **levanta.apk** e toque nele.
3. O Android vai avisar que o navegador não pode instalar apps. Toque em **Configurações**, ligue **Permitir desta fonte** e volte.
4. Toque em **Instalar**. Se o Play Protect perguntar, escolha **Instalar mesmo assim** (o app não está na loja, por isso o aviso).

Para atualizar, não precisa voltar ao site: quando existe versão nova, o app mostra um cartão **Versão nova disponível** na tela inicial. Toque em **Atualizar agora**, espere o download e confirme em **Atualizar** na tela do Android (na primeira vez ele pede para permitir instalações a partir do Levanta). A versão nova entra por cima e mantém alarmes e histórico. Em **Ajustes > Sobre o app** dá para conferir a versão instalada e procurar atualização na hora.

## Preparar o tablet (uma vez)

Ao abrir o app, um cartão amarelo lista o que falta liberar. Toque em **Liberar** em cada item:

- **Notificações** e **tela cheia:** é o que deixa o alarme abrir sozinho com o tablet bloqueado.
- **Economia de bateria:** escolha "Permitir". Sem isso a Samsung pode encerrar o app durante a noite.
- **Aparecer sobre outros apps** (recomendado): faz o alarme tomar a tela mesmo se você estiver usando o tablet.
- **Microfone** (opcional): deixa o tablet ouvir sua afirmação na missão surpresa. O reconhecimento de voz usa o serviço do próprio Android e normalmente precisa de internet; se falhar, a saída é montar a frase tocando nas palavras.

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
  - `Missions.kt`: questões, contas, frase, leitura de código, fala e memória.
- `app/src/main/assets/questoes.json`: o baralho de questões de certo ou errado. Cada item tem a afirmação, o gabarito e a explicação com o artigo.
  - `MainActivity.kt`, `Setup.kt`, `Ui.kt`: telas do app, permissões e componentes.
  - `Atualizacao.kt`: consulta a última versão publicada no GitHub, baixa o instalador e abre a instalação.
- `.github/workflows/build.yml`: a cada envio para `main`, o GitHub compila o APK, roda `ci/smoke.py` num emulador (alarme de ponta a ponta, com capturas de tela) e, se passar, publica uma versão nova. As missões de fala e de memória ficam fora desse teste: o emulador não fala nem enxerga a sequência.
- `ci/make_sounds.py`: gera os sons do alarme e da checagem.

Não é preciso ter o Android SDK no computador: a compilação acontece no GitHub. A chave de assinatura fica nos segredos do repositório (`KEYSTORE_B64` e `KEYSTORE_PASSWORD`).
