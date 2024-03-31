package uk.co.rafearnold.themind

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.FileLoader
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.SEE_OTHER
import org.http4k.core.Uri
import org.http4k.core.body.form
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson.auto
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.BiDiWsMessageLens
import org.http4k.lens.Header
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.PolyHandler
import org.http4k.server.asServer
import org.http4k.template.TemplateRenderer
import org.http4k.template.ViewModel
import org.http4k.template.ViewNotFound
import org.http4k.template.viewModel
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsFilter
import org.http4k.websocket.WsHandler
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsResponse
import org.http4k.websocket.then
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.StringWriter
import java.util.UUID
import org.http4k.routing.ws.bind as wsBind

val logger: Logger = LoggerFactory.getLogger("Main")

fun main() {
  startServer(GameConfig(12, 3, 1)).block()
}

fun startServer(
  gameConfig: GameConfig,
  port: Int = 8080,
): Http4kServer {
  val templateRenderer = PebbleTemplateRenderer()
  val view: BiDiBodyLens<ViewModel> =
    Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()
  val wsView: BiDiWsMessageLens<ViewModel> = WsMessage.viewModel(templateRenderer).toLens()
  val gameServer = SimpleServer(gameConfig = gameConfig)
  val players: MutableMap<String, Player> = mutableMapOf()
  val router =
    routes(
      Index(view, players),
      CreateGame(gameServer, players),
      JoinGame(gameServer, players),
    )
  val app =
    PolyHandler(
      ServerFilters.CatchAll { e ->
        logger.error("Error caught handling request", e)
        Response(Status.INTERNAL_SERVER_ERROR)
      }.then(router),
      WsFilter { next ->
        {
          try {
            next(it)
          } catch (e: Throwable) {
            logger.error("Error caught handling WS request", e)
            WsResponse {}
          }
        }
      }.then(Listen(gameServer, players, wsView)),
    )
  val server = app.asServer(Jetty(port = port)).start()
  logger.info("Server started")
  return server
}

class Index(view: BiDiBodyLens<ViewModel>, players: Map<String, Player>) : RoutingHttpHandler by
"/" bind GET to { request ->
  val player = request.player(players)
  val viewModel: ViewModel =
    if (player == null) {
      HomeViewModel
    } else {
      when (player.state) {
        is InLobby -> LobbyViewModel(gameId = player.gameId, isHost = player.isHost)
        is InGame -> TODO()
        is GameLost -> TODO()
        is GameWon -> TODO()
      }
    }
  Response(OK).with(view of viewModel)
}

class CreateGame(server: Server, players: MutableMap<String, Player>) : RoutingHttpHandler by
"/create" bind POST to {
  val player = server.createGame(playerName = it.form("playerName")!!)
  handleNewPlayer(player, players)
}

class JoinGame(server: Server, players: MutableMap<String, Player>) : RoutingHttpHandler by
"/join" bind POST to {
  val player = server.joinGame(playerName = it.form("playerName")!!, gameId = it.form("gameId")!!)
  handleNewPlayer(player, players)
}

class Listen(
  server: Server,
  players: Map<String, Player>,
  view: BiDiWsMessageLens<ViewModel>,
) : WsHandler by
  "/listen" wsBind { request ->
    logger.debug("WS request received")
    val player = request.player(players)!!
    WsResponse { ws: Websocket ->
      player.onUpdate { ws.sendView(player = player, playersVotingToThrowStar = it, view = view) }
      ws.onMessage {
        logger.debug("WS message received: ${it.bodyString()}")
        when (actionLens(it)) {
          Action.StartGame -> server.startGame(player)
          Action.PlayCard -> server.playCard(player)
          Action.VoteToThrowStar -> server.voteToThrowStar(player)
          Action.Heartbeat -> Unit // Do nothing.
        }
      }
      ws.onError { logger.error("Error caught handling WS request", it) }
    }
  }

private fun Websocket.sendView(
  player: Player,
  playersVotingToThrowStar: List<String>,
  view: BiDiWsMessageLens<ViewModel>,
) {
  val model: ViewModel =
    when (player.state) {
      is GameLost -> WsGameLostModel
      is GameWon -> WsGameWonModel
      is InGame ->
        WsGameViewModel(
          cards = player.cards.map { card -> card.value }.sorted(),
          playersVotingToThrowStar = playersVotingToThrowStar,
        )
      is InLobby -> TODO()
    }
  send(view(model))
}

private val actionLens: BiDiWsMessageLens<Action> = WsMessage.auto<Action>().toLens()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "action")
@JsonSubTypes(
  JsonSubTypes.Type(value = Action.StartGame::class, name = "start"),
  JsonSubTypes.Type(value = Action.PlayCard::class, name = "play"),
  JsonSubTypes.Type(value = Action.VoteToThrowStar::class, name = "vote"),
  JsonSubTypes.Type(value = Action.Heartbeat::class, name = "heartbeat"),
)
private sealed interface Action {
  data object StartGame : Action

  data object PlayCard : Action

  data object VoteToThrowStar : Action

  data object Heartbeat : Action
}

private fun handleNewPlayer(
  player: Player,
  players: MutableMap<String, Player>,
): Response {
  val playerId = UUID.randomUUID().toString()
  players[playerId] = player
  val cookie =
    Cookie(
      name = PLAYER_ID_COOKIE,
      value = playerId,
      httpOnly = true,
      sameSite = SameSite.Strict,
    )
  return redirectHome().cookie(cookie)
}

private fun redirectHome(): Response = Response(SEE_OTHER).with(Header.LOCATION.of(Uri.of("/")))

private fun Request.player(players: Map<String, Player>): Player? =
  cookie(PLAYER_ID_COOKIE)?.value?.let { players[it] }

private const val PLAYER_ID_COOKIE: String = "themind_playerid"

object HomeViewModel : ViewModel {
  override fun template(): String = "home"
}

data class LobbyViewModel(val gameId: String, val isHost: Boolean) : ViewModel {
  override fun template(): String = "lobby"
}

data class WsGameViewModel(
  val cards: List<Int>,
  val playersVotingToThrowStar: List<String>,
) : ViewModel {
  override fun template(): String = "ws-game"
}

object WsGameWonModel : ViewModel {
  override fun template(): String = "ws-won"
}

object WsGameLostModel : ViewModel {
  override fun template(): String = "ws-lost"
}

class PebbleTemplateRenderer(
  private val engine: PebbleEngine =
    PebbleEngine.Builder()
      .cacheActive(false)
      .loader(FileLoader().apply { prefix = "src/main/resources" })
      .build(),
) : TemplateRenderer {
  override fun invoke(viewModel: ViewModel): String =
    try {
      val writer = StringWriter()
      engine.getTemplate(viewModel.template() + ".html")
        .evaluate(writer, mapOf("model" to viewModel))
      writer.toString()
    } catch (e: LoaderException) {
      throw ViewNotFound(viewModel)
    }
}

interface Server {

  fun createGame(playerName: String = UUID.randomUUID().toString()): Player

  fun joinGame(
    playerName: String = UUID.randomUUID().toString(),
    gameId: String,
  ): Player

  fun startGame(player: Player)

  fun playCard(player: Player)

  fun voteToThrowStar(player: Player)
}

data class Player(
  val name: String,
  val gameId: String,
  val isHost: Boolean,
  var state: ClientState = InLobby,
) {
  private val updateHandlers: MutableList<(playersVotingToThrowStar: List<String>) -> Unit> =
    mutableListOf()

  fun triggerUpdate(playersVotingToThrowStar: List<String>) =
    updateHandlers.forEach { it(playersVotingToThrowStar) }

  fun onUpdate(fn: (playersVotingToThrowStar: List<String>) -> Unit) {
    updateHandlers.add(fn)
  }
}

sealed interface ClientState

data object InLobby : ClientState

data class InGame(
  var cards: MutableList<Card>,
  var lives: Int,
  var stars: Int,
  var votingToThrowStar: Boolean,
) : ClientState

data object GameWon : ClientState

data object GameLost : ClientState

data class Card(val value: Int)

data class GameConfig(val roundCount: Int, val startingLivesCount: Int, val startingStarsCount: Int)

class SimpleServer(private val gameConfig: GameConfig) : Server {

  private val games: MutableList<Game> = mutableListOf()

  override fun createGame(playerName: String): Player {
    val gameId = UUID.randomUUID().toString()
    val host = Player(name = playerName, gameId = gameId, isHost = true)
    val game = Game(id = gameId, players = mutableListOf(host), currentRound = 1)
    games.add(game)
    return host
  }

  override fun joinGame(
    playerName: String,
    gameId: String,
  ): Player {
    val game = getGame(gameId = gameId)
    val player = Player(name = playerName, gameId = gameId, isHost = false)
    game.players.add(player)
    return player
  }

  override fun startGame(player: Player) {
    val game = getGame(player = player)
    val deck = shuffledDeck()
    for (@Suppress("NAME_SHADOWING") player in game.players) {
      player.state =
        InGame(
          cards = mutableListOf(Card(deck.next())),
          lives = gameConfig.startingLivesCount,
          stars = gameConfig.startingStarsCount,
          votingToThrowStar = false,
        )
    }
    game.triggerUpdate()
  }

  override fun playCard(player: Player) {
    val game = getGame(player = player)
    val cards = player.cards
    val removedCard = cards.minByOrNull { it.value }!!
    cards.remove(removedCard)
    if (removedCard.value >
      (game.players.flatMap { it.cards.map { card -> card.value } }.minOrNull() ?: Int.MAX_VALUE)
    ) {
      // A mistake was made.
      game.players.forEach { it.lives-- }
      if (game.players.first().lives == 0) {
        game.setState(GameLost)
        game.triggerUpdate()
        return
      } else {
        for (@Suppress("NAME_SHADOWING") player in game.players) {
          player.cards.removeAll { it.value < removedCard.value }
        }
      }
    }
    game.players.forEach { it.votingToThrowStar = false }
    game.handlePossibleRoundComplete()
    game.triggerUpdate()
  }

  override fun voteToThrowStar(player: Player) {
    val game = getGame(player)
    player.votingToThrowStar = true
    if (game.players.all { it.votingToThrowStar }) {
      for (@Suppress("NAME_SHADOWING") player in game.players) {
        player.stars--
        player.cards.apply { remove(minByOrNull { it.value }) }
        player.votingToThrowStar = false
      }
      game.handlePossibleRoundComplete()
    }
    game.triggerUpdate()
  }

  private fun Game.handlePossibleRoundComplete() {
    if (players.all { it.cards.isEmpty() }) {
      // Round complete.
      if (currentRound == gameConfig.roundCount) {
        setState(GameWon)
      } else {
        currentRound++
        val deck = shuffledDeck()
        for (player in players) {
          player.cards = (1..currentRound).map { Card(deck.next()) }.toMutableList()
        }
      }
    }
  }

  private fun Game.setState(state: ClientState) {
    for (player in players) {
      player.state = state
    }
  }

  private fun getGame(player: Player): Game = getGame(gameId = player.gameId)

  private fun getGame(gameId: String): Game = games.first { it.id == gameId }

  private fun shuffledDeck(): Iterator<Int> = (1..100).shuffled().iterator()

  private fun Game.triggerUpdate() {
    val playersVotingToThrowStar =
      players.filter { if (it.state is InGame) it.votingToThrowStar else false }.map { it.name }
    players.forEach { it.triggerUpdate(playersVotingToThrowStar) }
  }
}

private data class Game(val id: String, val players: MutableList<Player>, var currentRound: Int)

private var Player.cards: MutableList<Card>
  get() = (state as InGame).cards
  set(cards) {
    (state as InGame).cards = cards
  }

private var Player.lives: Int
  get() = (state as InGame).lives
  set(value) {
    (state as InGame).lives = value
  }

private var Player.stars: Int
  get() = (state as InGame).stars
  set(value) {
    (state as InGame).stars = value
  }

private var Player.votingToThrowStar: Boolean
  get() = (state as InGame).votingToThrowStar
  set(value) {
    (state as InGame).votingToThrowStar = value
  }
