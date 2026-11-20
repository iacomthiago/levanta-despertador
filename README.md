# Disciplina

App de disciplina para o dia inteiro, para Android. Nasceu como o despertador **Levanta** e cresceu: agora cada parte da rotina é uma missão com horário, que rende experiência, sobe o nível e mantém uma sequência de dias.

Feito para ficar num tablet **longe da cama**, no carregador.

## Missões

Ao criar uma missão você escolhe o tipo:

- **Levantar:** o despertador. Toca e **só para com a missão cumprida** (detalhes abaixo). Vale 30 XP.
- **Estudo:** 14 blocos de 30 minutos (7 horas) para marcar com um toque: estudou meia hora, toca num bloco; tocar de novo desmarca. Não há nada para escrever. Cada bloco vale 25 XP. O dia de estudo conta para a sequência quando chega à meta de blocos (4 por padrão, ajustável), e bater a meta antes das 14 h dá mais 50 XP.
- **Treino:** na hora, abre o treino no navegador. Na volta, o botão **Concluí** libera depois de uma parte da duração prevista (60% por padrão, ajustável em cada missão). Vale 40 XP.
- **Simples:** tarefa rápida com nome livre, concluída com um toque. Vale 10 XP.

Os alarmes que não são o de levantar tocam forte, mas **param com um toque**, que já abre a missão. Se ninguém atender em 3 minutos, o som para e fica um lembrete.

## Progresso

- **Experiência e nível:** cada nível pede 50 XP a mais que o anterior. As faixas de nível têm o nome das marchas (Partida, Primeira marcha, Segunda marcha...) e mudam a cor da borda da imagem do personagem.
- **Sequência de dias:** cada missão tem a opção **Conta para a sequência**. Um dia em que todas as marcadas foram concluídas soma um; uma marcada sem concluir até o fim do dia zera. Às 19 h o app avisa se ainda falta alguma.
- **Recompensas:** a cada 5 níveis sai uma recompensa: um **turbo** (um dia inteiro valendo o dobro de XP, ligado quando você quiser) e o **prêmio da vida real** daquele nível (a lista já vem pronta, de um lanche a um passeio, e dá para trocar). A cada 7 dias de sequência sai mais um turbo.
- **Missão da semana:** de segunda a domingo, um desafio (treinar em 5 dias, bater a meta de estudo em 5 dias...) que vale 150 XP.
- **Ideias para depois:** uma caixa para anotar a ideia que apareceu no meio de uma missão e voltar ao que estava fazendo.

A imagem do personagem pode ser trocada em **Ajustes > Seu personagem**, escolhendo da galeria. Ela fica só no aparelho.

Quem já usava o Levanta não perde nada ao atualizar: os alarmes viram missões de levantar, os ajustes continuam e os despertares cumpridos viram experiência.

## O despertador (missão de levantar)

1. **Toca na hora.** Acende a tela por cima do bloqueio e sobe até o volume máximo escolhido em Ajustes (75% do volume do aparelho, por padrão). Começa com a abertura da série HOTARU; se a missão não for cumprida em 60 segundos, a música vira um bipe insistente. Os botões de volume do aparelho não fazem nada enquanto ele toca.
2. **Missão para desligar.** Uma ou mais, na ordem:
   - **Código em outro cômodo:** ler com a câmera um código de barras ou QR code que você cadastrou (a pasta de dente no banheiro, por exemplo).
   - **Questões do concurso:** afirmações de certo ou errado sobre Constituição e Licitações, até somar o número de acertos pedido. Errou, lê a explicação e responde outra. Primeiro aparecem as que você errou e as que ainda não viu.
   - **Contas:** resolver contas num teclado próprio na tela.
   - **Frase:** montar sua frase de compromisso tocando nas palavras na ordem certa.
   - **Falar a afirmação:** uma extra a cada despertar. Você fala sua afirmação em voz alta para o tablet ouvir, e ele responde em voz alta com uma frase de apoio. Se o tablet não conseguir ouvir (microfone não liberado ou sem reconhecimento de voz), entra no lugar uma sequência de quadros que acendem, para repetir de memória.
3. **Trégua.** Enquanto você está resolvendo, o som fica baixo. Parou de mexer por 30 segundos, o volume volta. O teto é de 3 minutos por missão.
4. **Checagem.** Minutos depois, o tablet pergunta "você continua de pé?". Sem resposta em 45 segundos, é recaída: o alarme volta e a missão recomeça.
5. **Fim.** Passou pela checagem, o despertar entra no histórico, a tela de bom dia mostra quantos dias faltam para a prova e, se você quiser, o app do Claude abre em seguida.

Na véspera, o app também avisa a **hora de dormir**: um toque meia hora antes e outro na hora de deitar, calculados para você dormir as horas combinadas (8 por padrão) antes do próximo alarme.

Se ninguém atender em 20 minutos, o alarme desiste e registra "sem resposta" no histórico. Reiniciar o tablet no meio não adianta: ao ligar de novo, o alarme retoma.

Tudo é configurável em **Ajustes**: quais missões, quantos acertos e quantas contas, a frase, a afirmação, a data da prova, número de checagens e intervalo, sonecas permitidas (o padrão é nenhuma), música de abertura, volume crescente e volume máximo do alarme. Lá também dá para treinar a missão de falar e a de memória fora do alarme.

## Instalar no tablet

1. No tablet, abra a página de versões do repositório: https://github.com/iacomthiago/levanta-despertador/releases/latest
2. Baixe o arquivo **levanta.apk** e toque nele (o arquivo manteve o nome antigo para a atualização entrar por cima).
3. O Android vai avisar que o navegador não pode instalar apps. Toque em **Configurações**, ligue **Permitir desta fonte** e volte.
4. Toque em **Instalar**. Se o Play Protect perguntar, escolha **Instalar mesmo assim** (o app não está na loja, por isso o aviso).

Para atualizar, não precisa voltar ao site: quando existe versão nova, o app mostra um cartão **Versão nova disponível** na tela inicial. Toque em **Atualizar agora**, espere o download e confirme em **Atualizar** na tela do Android (na primeira vez ele pede para permitir instalações a partir do Disciplina). A versão nova entra por cima e mantém missões, nível e histórico. Em **Ajustes > Sobre o app** dá para conferir a versão instalada e procurar atualização na hora.

## Preparar o tablet (uma vez)

Ao abrir o app, um cartão amarelo lista o que falta liberar. Toque em **Liberar** em cada item:

- **Notificações** e **tela cheia:** é o que deixa o alarme abrir sozinho com o tablet bloqueado.
- **Economia de bateria:** escolha "Permitir". Sem isso a Samsung pode encerrar o app durante a noite.
- **Aparecer sobre outros apps** (recomendado): faz o alarme tomar a tela mesmo se você estiver usando o tablet.
- **Microfone** (opcional): deixa o tablet ouvir sua afirmação. O reconhecimento de voz usa o serviço do próprio Android e normalmente precisa de internet; se falhar, a saída é montar a frase tocando nas palavras.

Em tablets Samsung, confira também em **Configurações > Bateria > Limites de uso em segundo plano** que o Disciplina não está em "Apps em suspensão" nem em "Apps em suspensão profunda".

Depois:

1. Crie uma missão de levantar e toque em **Testar agora**. Bloqueie a tela e veja o alarme abrir sozinho. No teste, a checagem chega em 20 segundos e nada entra no histórico.
2. Deixe o volume de **alarme** do tablet alto e o tablet **no carregador** durante a noite.
3. Coloque o tablet longe da cama.

## No celular e no computador (site)

O Disciplina também abre no navegador, em **https://iacomthiago.github.io/levanta-despertador/**, inclusive no iPhone (Compartilhar > Adicionar à Tela de Início). O site tem as missões de estudo, treino e simples, os blocos de 30 minutos, XP, nível, sequência, missão da semana, recompensas e ideias. Não tem o despertador: site não toca alarme.

Para ter o mesmo progresso em mais de um aparelho, ligue a sincronização uma vez em cada um:

1. No site, toque em Ajustes > Ligar a sincronização e siga os três passos. O progresso fica numa nota secreta (gist) da sua conta do GitHub; a chave tem permissão só para isso.
2. Nos outros aparelhos, não precisa digitar: no site, Ajustes > Ligar outro aparelho mostra um QR code. No tablet, Ajustes > Sincronização com o site > Ligar a sincronização lê esse código; no celular, basta apontar a câmera.

Depois disso é sozinho: cada mudança sobe e cada abertura busca o que mudou. Se dois aparelhos mudarem no mesmo dia, nada se perde: um bloco marcado no site e outro no tablet aparecem nos dois. Cada pessoa usa a própria conta do GitHub, então o progresso de uma não se mistura com o de outra.

## Limites

- Desligar o tablet, forçar a parada do app nas configurações ou desinstalar silencia o alarme. Nenhum app consegue impedir isso.
- Se o tablet ficar sem bateria, não toca. Por isso o carregador.
- Quando você dormir fora de casa, use o alarme normal do celular.

## Desenvolvimento

O projeto é Kotlin com Jetpack Compose, sem dependência de serviços do Google.

- `app/src/main/java/com/iacomthiago/levanta/`
  - `Data.kt`: missões (alarmes), ajustes, despertar em andamento e histórico (SharedPreferences).
  - `Progresso.kt`: experiência, nível, sequência de dias, blocos de estudo, missão da semana e a conversão dos dados do Levanta.
  - `Disciplina.kt`: peças da tela inicial e as telas de estudo, treino, diário e ideias.
  - `Scheduler.kt`: agendamento no AlarmManager e os receptores de alarme e de reinício.
  - `AlarmService.kt`: som, volume e a máquina de fases do despertar.
  - `AlarmActivity.kt`: a tela que abre por cima do bloqueio.
  - `Missions.kt`: questões, contas, frase, leitura de código, fala e memória.
- `app/src/main/assets/questoes.json`: o baralho de questões de certo ou errado. Cada item tem a afirmação, o gabarito e a explicação com o artigo.
  - `MainActivity.kt`, `Setup.kt`, `Ui.kt`: telas do app, permissões e componentes.
  - `Atualizacao.kt`: consulta a última versão publicada no GitHub, baixa o instalador e abre a instalação.
- `.github/workflows/build.yml`: a cada envio para `main`, o GitHub compila o APK, roda `ci/smoke.py` num emulador (despertador de ponta a ponta e as telas de missões, com capturas de tela) e, se passar, publica uma versão nova. As missões de fala e de memória ficam fora desse teste: o emulador não fala nem enxerga a sequência.
- `ci/make_sounds.py`: gera os sons do alarme e da checagem.
- `web/`: o site (HTML e JavaScript puro, sem etapa de compilação). `nucleo.js` tem as regras e a junção de dados; `app.js`, as telas; `nuvem.js`, a conversa com o GitHub; `qr.js`, o QR code. `teste.html` roda as conferências das regras no navegador.
- `Sync.kt` e `SyncNucleo.kt`: a sincronização no app. `SyncNucleo.kt` é a mesma conta de `web/nucleo.js`; o teste `NucleoTest` compara os dois caso a caso a cada compilação (os casos saem de `web/vetores.js`). Nada do despertador chama a sincronização, e os alarmes de levantar nunca são alterados por ela.
- `.github/workflows/site.yml`: publica a pasta `web` no ramo `gh-pages` a cada mudança. Mudança só no site não gera versão nova do app.
- `ci/mock_gist.py`: imitação da API de gists para testar a sincronização do site sem conta. `ci/make_web_assets.py`: gera os ícones do site.

Formato dos dados sincronizados: um JSON por conta em que cada item (bloco, missão concluída, ideia, missão, ajuste) leva a hora da última mudança. Juntar dois aparelhos fica com a mudança mais recente de cada item. O XP não viaja como número: é recalculado dos registros, mais uma base fixa com o que o tablet já tinha no dia em que entrou na conta.

Não é preciso ter o Android SDK no computador: a compilação acontece no GitHub. A chave de assinatura fica nos segredos do repositório (`KEYSTORE_B64` e `KEYSTORE_PASSWORD`).
