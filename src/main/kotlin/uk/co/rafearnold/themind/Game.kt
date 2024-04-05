package uk.co.rafearnold.themind

import java.io.Closeable
import java.util.UUID

interface ActionReceiver {
  fun receive(action: Action)
}

interface EventEmitter {
  fun listen(fn: () -> Unit): Listener
}

fun interface Listener : Closeable

interface Server {

  fun createGame(playerName: String): GameConnection

  fun joinGame(
    gameId: String,
    playerName: String,
  ): GameConnection

  fun getConnection(playerId: String): GameConnection?

  fun startGame(playerId: String)

  fun playCard(playerId: String)

  fun voteToThrowStar(playerId: String)

  fun leave(playerId: String)
}

data class GameConnection(
  val server: Server,
  val gameId: String,
  val player: Player,
  var state: GameState,
) : ActionReceiver, EventEmitter {
  private val listeners: MutableMap<String, () -> Unit> = mutableMapOf()

  override fun receive(action: Action) {
    when (action) {
      Action.PlayCard -> server.playCard(playerId = player.id)
      Action.StartGame -> server.startGame(playerId = player.id)
      Action.VoteToThrowStar -> server.voteToThrowStar(playerId = player.id)
    }
  }

  override fun listen(fn: () -> Unit): Listener {
    val listenerId: String = UUID.randomUUID().toString()
    listeners[listenerId] = fn
    return Listener { listeners.remove(listenerId) }
  }

  fun triggerUpdate() = listeners.values.forEach { it() }
}

data class Player(val id: String, val name: String, var isHost: Boolean)

data class OtherPlayer(
  val id: String,
  val name: String,
  var isVotingToThrowStar: Boolean,
  var cardCount: Int,
)

sealed interface GameState

data class InLobby(
  val allPlayers: MutableList<Player>,
) : GameState

data class InGame(
  val otherPlayers: MutableList<OtherPlayer>,
  var currentRound: Int,
  var cards: MutableList<Card>,
  var lives: Int,
  var stars: Int,
  var isVotingToThrowStar: Boolean,
) : GameState

data object GameWon : GameState

data object GameLost : GameState

data class PlayerLeft(val playerName: String) : GameState

data class Card(val value: Int)

data class GameConfig(val roundCount: Int, val startingLivesCount: Int, val startingStarsCount: Int)

sealed interface Action {
  data object StartGame : Action

  data object PlayCard : Action

  data object VoteToThrowStar : Action
}
