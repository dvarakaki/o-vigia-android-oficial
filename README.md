# O Vigia

Jogo estilo Akinator para Android: você pensa em um personagem da Marvel e o
Vigia tenta adivinhar com perguntas de sim/não.

## Como rodar

Requisitos: Android Studio (JDK 17+ embutido) e Android SDK 36.

1. Crie uma chave gratuita em <https://comicvine.gamespot.com/api/>.
2. Adicione a chave ao `local.properties` na raiz do projeto (arquivo fora do git):

   ```properties
   COMIC_VINE_API_KEY=sua_chave_aqui
   ```

3. Rode pelo Android Studio ou pela linha de comando:

   ```bash
   ./gradlew installDebug
   ```

O build de debug usa o id `com.ovigia.app.debug` e pode ficar instalado ao lado do de release.

### Amigos online (Firebase)

A aba de amigos (pedidos de amizade, perfil dos amigos, heróis desbloqueados e conquistas)
usa Firebase Auth + Cloud Firestore. Sem configuração o app compila e roda normalmente, e
a aba avisa que os amigos não estão disponíveis. Tudo cabe no plano gratuito (Spark): foto
e banner vão reduzidos dentro dos documentos, sem Cloud Storage nem Cloud Functions.

1. Crie um projeto no [console do Firebase](https://console.firebase.google.com/).
2. Adicione um app Android com o pacote `com.ovigia.app` e outro com `com.ovigia.app.debug`
   (o mesmo `google-services.json` serve para os dois).
3. Em **Authentication → Método de login**, ative **E-mail/senha**.
4. Em **Firestore Database**, crie o banco e publique as regras de [`firestore.rules`](firestore.rules)
   (cole na aba **Regras** ou rode `firebase deploy --only firestore:rules`).
5. Baixe o `google-services.json` para `app/` (fora do git) e rode o app de novo.

Para desenvolver sem projeto real, use o Firebase Local Emulator Suite:

```bash
firebase emulators:start --only auth,firestore --project demo-ovigia
```

e aponte o build de debug para ele no `local.properties` (`10.0.2.2` é o computador visto
pelo emulador Android):

```properties
FIREBASE_EMULATOR_HOST=10.0.2.2
```

Como funciona:

- **A conta online usa o mesmo e-mail e senha da local.** Entrar ou criar a conta já abre a
  sessão online com a senha digitada, então a aba de amigos não pede a senha de novo — só
  falta escolher um `@usuario` único. Sem servidor configurado ou sem internet no login, a
  aba pede a senha quando o jogador abrir.
- **Só amigos veem o perfil completo.** Nome, `@usuario` e foto aparecem na busca; bio,
  banner, números, heróis e conquistas só para amigos. O e-mail nunca é publicado.
- **Amizade só com pedido aceito.** As regras do Firestore garantem que só quem recebeu o
  pedido cria a amizade, e qualquer um dos dois pode desfazê-la.
- **A conta acompanha o jogador.** Entrar com um e-mail que já tem conta online num aparelho
  onde ela não existe (app reinstalado, celular novo) recria a conta local com o que o
  servidor guardava: nome, bio, `@usuario`, foto, banner, heróis e números das partidas. O
  aprendizado do motor e o histórico partida a partida ficam no aparelho onde foram jogados.
- **Excluir a conta apaga também a online.** Sem internet, a exclusão espera a conexão para
  não deixar dados para trás.

### Comandos úteis

| Comando | O que faz |
|---|---|
| `./gradlew testDebugUnitTest` | Testes JVM (motor, ViewModel, aprendizado, dados curados, tradução, regras do R8) |
| `./gradlew connectedDebugAndroidTest` | Testes que precisam de aparelho (o tradutor do ML Kit) |
| `./gradlew lintDebug` | Lint (quebra o build em erro) |
| `./gradlew assembleRelease` | APK de release com R8 (encolhido e ofuscado) |

Tudo que o Gson lê ou grava em JSON precisa de regra em `app/src/main/keepRules/rules.keep`: sem ela o R8
renomeia os campos e apaga o tipo genérico, e os dados voltam do disco com o tipo errado só depois de reabrir
o app — coisa que os testes JVM não enxergam. O `KeepRulesTest` confere isso; ao criar um arquivo JSON novo,
acrescente a classe raiz à lista dele.

## Publicação

### Assinatura

Crie um `keystore.properties` na raiz (fora do git):

```properties
storeFile=caminho/para/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

No CI, use as variáveis de ambiente `OVIGIA_STOREFILE`, `OVIGIA_STOREPASSWORD`,
`OVIGIA_KEYALIAS` e `OVIGIA_KEYPASSWORD`. Sem nenhum dos dois, o release sai sem assinatura.

A versão fica em `gradle.properties` (`ovigia.versionCode` / `ovigia.versionName`).
Incremente o `versionCode` a cada envio para a loja.

### Chave da API em produção

Tudo que vai no `BuildConfig` pode ser extraído do APK. Antes de publicar, coloque
um proxy seu (Cloudflare Worker, Cloud Function etc.) na frente da Comic Vine que
injete a chave no servidor, e configure:

```properties
COMIC_VINE_BASE_URL=https://seu-proxy.exemplo.com/api/
COMIC_VINE_API_KEY=
```

Com a URL apontando para um proxy, o app funciona sem chave embutida.

## Arquitetura

```
app/src/main/
├── assets/roster.json        Elenco curado: times, poderes, vilania, reconhecimento
├── java/com/ovigia/app/
│   ├── MainActivity           Activity única (Navigation + fragments)
│   ├── AppContainer           Injeção de dependências manual
│   ├── engine/                Motor bayesiano — Java puro, sem Android
│   ├── game/                  GameViewModel, estado de UI e eventos
│   ├── data/                  Repositório (Comic Vine + cache), mapeamento, perguntas
│   ├── learning/              Aprendizado persistente entre partidas
│   ├── auth/                  Contas locais (PBKDF2) e ViewModel de login/cadastro
│   ├── collection/            Heróis desbloqueados, por conta
│   ├── catalog/               Catálogo e ficha do herói (regra de desbloqueio, ViewModels)
│   ├── profile/               Perfil e edição do perfil (foto, banner, dados, senha)
│   ├── social/                Amigos online: Firebase, @usuario, pedidos, conquistas e ViewModels
│   ├── settings/              Configurações: idioma, preferências da partida, cache
│   ├── translation/           Tradução dos textos da Comic Vine (glossário + tradutor do aparelho)
│   └── ui/                    Fragments: início, login, perfil, amigos, catálogo, ficha, partida, resultado e configurações
├── res/values*/strings.xml    Textos em português (base), inglês, espanhol e francês
├── res/values*/glossary.xml   Poderes e origens da Comic Vine nos mesmos idiomas
└── res/navigation/nav_main.xml   Fluxo de telas
```

- **MVVM com Activity única.** O `GameViewModel` vive no escopo do grafo de navegação
  `game_graph`: nasce ao entrar na partida e é destruído ao sair dela.
- **Sobrevive à morte do processo.** Cada jogada vai para um log no `SavedStateHandle`.
  Ao restaurar, o log é reaplicado no motor e a mesma pergunta volta à tela.
- **I/O fora da main thread.** Disco e rede rodam num executor de I/O; as gravações são
  atômicas (arquivo temporário + rename). O StrictMode fica ligado no debug.
- **Rede dos amigos em thread própria.** O Firebase roda num executor separado, para uma
  conexão lenta não segurar as gravações em disco. O `SocialBackend` é uma interface: os
  testes usam um servidor em memória com as mesmas garantias do `firestore.rules`.

## Como o Vigia pensa

1. **Prior.** Cada personagem começa com probabilidade proporcional a
   `log(2 + aparições em quadrinhos)`. Personagens mainstream ganham ×4, e os que você
   já escolheu ganham um bônus logarítmico.
2. **Modelo de resposta.** Cada resposta tem uma probabilidade de ser dada por quem tem
   o traço e por quem não tem (ex.: "Sim" 80% contra 3%). A verossimilhança é a mistura
   ponderada pela crença do personagem no traço. Um erro do jogador enfraquece um
   candidato, mas nunca o elimina.
3. **Escolha da pergunta.** O Vigia escolhe a pergunta que minimiza a entropia esperada,
   somando os quatro desfechos possíveis. Ele sorteia entre as melhores para variar as
   partidas. Quando um líder se destaca, passa a escolher a pergunta que melhor separa
   esse líder dos demais.
4. **Hora de chutar.** Só depois de 8 perguntas (contadas de novo após cada chute errado),
   e apenas se o líder passar de 85% ou estiver bem à frente do 2º e do 3º colocados.
5. **Aprendizado.** Ao fim de cada partida com o personagem conhecido (acerto, escolha
   entre alternativas ou revelação após derrota), as respostas dadas corrigem as crenças
   daquele personagem. Isso vale inclusive para atributos que a curadoria esqueceu.

## Dados curados

`assets/roster.json` define o elenco. Para adicionar um personagem:

```json
{"id": 1440, "name": "Wolverine", "teams": ["xmen"], "powers": ["forca", "cura", "armas", "sentidos"], "villain": 0.08, "mainstream": true}
```

- `id`: id do personagem na Comic Vine.
- `powers` e `teams`: precisam existir em `data/QuestionKeys`.
- `villain`: crença entre 0 e 1. Use valores intermediários para anti-heróis.

O `RosterCatalogTest` valida o arquivo. Uma pergunta nova exige a chave em
`QuestionKeys`, o texto `q_<chave>` em `strings.xml` (e em cada `values-*/strings.xml`)
e a linha correspondente em `QuestionTexts`; o `QuestionTextsTest` confere tudo.

## Idiomas

O app é traduzido para português (idioma base, `values/`), inglês, espanhol e francês.
O jogador escolhe o idioma em **Configurações** (ou, no Android 13+, nas configurações do
sistema para o app); "Automático" segue o idioma do aparelho. A escolha usa o idioma por app
do AppCompat, então a tela é recriada já traduzida — inclusive as perguntas da próxima partida.

Para adicionar um idioma:

1. Crie `res/values-<idioma>/strings.xml` com todos os textos traduzíveis (o lint quebra o
   build se faltar algum), inclusive `content_language` com o código do idioma.
2. Traduza `res/values-<idioma>/glossary.xml` (poderes e origens da Comic Vine).
3. Adicione o idioma em `settings/AppLanguage`, em `res/xml/locales_config.xml` e o nome
   dele em `strings.xml` (`language_*`, escrito no próprio idioma).

O `AppLanguageTest` e o `ComicVineGlossaryTest` conferem se os lugares estão de acordo. A
marca "O Vigia" não é traduzida; nas frases, o personagem usa o nome oficial em cada idioma
(the Watcher, el Vigilante, le Gardien).

### Os textos da Comic Vine

A API só existe em inglês, e a ficha do herói é quase toda feita dela. A tradução acontece
em duas frentes, ambas sem custo e sem chave:

- **Vocabulário fechado** (128 poderes, 10 origens e as galerias de imagem): traduzido à mão
  em `res/values*/glossary.xml` e ligado aos ids da API em `translation/ComicVineGlossary`.
  Aparece pronto, na hora, e sem os erros que um tradutor automático cometeria sem contexto
  ("Power Suit", "Feral"). Termo novo na API aparece em inglês até alguém traduzi-lo ali.
- **Texto livre** (resumo, biografia, nascimento em texto e galerias sem nome próprio):
  traduzido pelo **ML Kit dentro do aparelho**. O pacote de cada idioma tem cerca de 30 MB,
  é baixado uma vez (em rede móvel, só com o aval do jogador) e depois funciona offline.

Nascimentos escritos como data ("Oct 14, 1962") viram data no formato do idioma, sem passar
por tradutor nenhum.

A biografia de um herói famoso passa de 200 mil caracteres e leva minutos para ser traduzida.
Por isso ela é recortada em trechos (`translation/HtmlTextRuns`, que preserva imagens, tabelas
e links), traduzida trecho a trecho e guardada em `files/hero_translations/{id}_{idioma}.json`:
o jogador vê o andamento, sair da tela cancela o trabalho (bateria) e voltar continua de onde
parou. Da segunda vez a ficha já abre traduzida. A barra no topo da ficha diz o que está
acontecendo e oferece "Ver original" a qualquer momento.

As traduções entram no "Limpar cache" das configurações; o pacote de idioma não, porque ele
fica fora do app e baixá-lo de novo custaria outros 30 MB.

> O tradutor do ML Kit é uma biblioteca nativa: ela soma cerca de 15 MB por ABI ao download
> do app na loja (o AAB entrega só a ABI do aparelho).
