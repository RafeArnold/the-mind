package uk.co.rafearnold.themind

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
import org.http4k.template.ViewModel
import org.http4k.template.viewModel
import org.http4k.websocket.Websocket
import org.http4k.websocket.WsFilter
import org.http4k.websocket.WsHandler
import org.http4k.websocket.WsMessage
import org.http4k.websocket.WsResponse
import org.http4k.websocket.then
import org.http4k.routing.ws.bind as wsBind

fun startServer(
  gameConfig: GameConfig,
  port: Int = 8080,
): Http4kServer {
  val templateRenderer = PebbleTemplateRenderer()
  val view: BiDiBodyLens<ViewModel> =
    Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()
  val wsView: BiDiWsMessageLens<ViewModel> = WsMessage.viewModel(templateRenderer).toLens()
  val gameServer = InMemoryServer(gameConfig = gameConfig)
  val router =
    routes(
      Index(view, gameServer),
      CreateGame(gameServer),
      JoinGame(gameServer),
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
      }.then(Listen(gameServer, wsView)),
    )
  val server = app.asServer(Jetty(port = port)).start()
  logger.info("Server started")
  return server
}

class Index(
  view: BiDiBodyLens<ViewModel>,
  server: Server,
) : RoutingHttpHandler by "/" bind GET to { request ->
    val connection = server.connect(request)
    val viewModel: ViewModel =
      if (connection == null) {
        HomeViewModel
      } else {
        when (connection.state) {
          is InLobby ->
            LobbyViewModel(
              gameId = connection.gameId,
              isHost = connection.player.isHost,
              allPlayers = (connection.state as InLobby).allPlayers,
            )
          is InGame ->
            GameViewModel(
              currentLivesCount = connection.lives,
              currentThrowingStarsCount = connection.stars,
              cards = connection.cards.map { card -> card.value }.sorted(),
              playersVotingToThrowStar = connection.playersVotingToThrowStar,
            )
          is GameLost -> TODO()
          is GameWon -> TODO()
        }
      }
    Response(OK).with(view of viewModel)
  }

class CreateGame(server: Server) : RoutingHttpHandler by "/create" bind POST to {
  val player = server.createGame(playerName = it.form("playerName")!!)
  handleNewPlayer(player)
}

class JoinGame(server: Server) : RoutingHttpHandler by "/join" bind POST to {
  val player = server.joinGame(gameId = it.form("gameId")!!, playerName = it.form("playerName")!!)
  handleNewPlayer(player)
}

class Listen(
  server: Server,
  view: BiDiWsMessageLens<ViewModel>,
) : WsHandler by "/listen" wsBind { request ->
    logger.debug("WS request received")
    val connection = server.connect(request)!!
    logger.debug("Player ${connection.player.name} connected")
    WsResponse { ws: Websocket ->
      val listener = connection.listen { ws.sendView(connection = connection, view = view) }
      ws.onMessage {
        logger.debug("WS message received: ${it.bodyString()}")
        val action =
          when (actionLens(it)) {
            WsAction.StartGame -> Action.StartGame
            WsAction.PlayCard -> Action.PlayCard
            WsAction.VoteToThrowStar -> Action.VoteToThrowStar
            WsAction.Heartbeat -> return@onMessage // Do nothing.
          }
        connection.receive(action)
      }
      ws.onError { logger.error("Error caught handling WS request", it) }
      ws.onClose {
        logger.debug("Player ${connection.player.name} disconnected")
        listener.close()
      }
    }
  }

private fun Websocket.sendView(
  connection: GameConnection,
  view: BiDiWsMessageLens<ViewModel>,
) {
  val model: ViewModel =
    when (connection.state) {
      is GameLost -> WsGameLostModel
      is GameWon -> WsGameWonModel
      is InGame ->
        WsGameViewModel(
          currentLivesCount = connection.lives,
          currentThrowingStarsCount = connection.stars,
          cards = connection.cards.map { card -> card.value }.sorted(),
          playersVotingToThrowStar = connection.playersVotingToThrowStar,
        )
      is InLobby ->
        WsLobbyViewModel(
          gameId = connection.gameId,
          isHost = connection.player.isHost,
          allPlayers = (connection.state as InLobby).allPlayers,
        )
    }
  send(view(model))
}

private val actionLens: BiDiWsMessageLens<WsAction> = WsMessage.auto<WsAction>().toLens()

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "action")
@JsonSubTypes(
  JsonSubTypes.Type(value = WsAction.StartGame::class, name = "start"),
  JsonSubTypes.Type(value = WsAction.PlayCard::class, name = "play"),
  JsonSubTypes.Type(value = WsAction.VoteToThrowStar::class, name = "vote"),
  JsonSubTypes.Type(value = WsAction.Heartbeat::class, name = "heartbeat"),
)
private sealed interface WsAction {
  data object StartGame : WsAction

  data object PlayCard : WsAction

  data object VoteToThrowStar : WsAction

  data object Heartbeat : WsAction
}

private fun handleNewPlayer(player: GameConnection): Response {
  val cookie =
    Cookie(
      name = PLAYER_ID_COOKIE,
      value = player.playerId,
      httpOnly = true,
      sameSite = SameSite.Strict,
    )
  return redirectHome().cookie(cookie)
}

private fun redirectHome(): Response = Response(SEE_OTHER).with(Header.LOCATION.of(Uri.of("/")))

private fun Server.connect(request: Request): GameConnection? =
  request.cookie(PLAYER_ID_COOKIE)?.value?.let { connect(playerId = it) }

private const val PLAYER_ID_COOKIE: String = "themind_playerid"
