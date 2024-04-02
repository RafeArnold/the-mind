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

  fun connect(playerId: String): GameConnection

  fun startGame(playerId: String)

  fun playCard(playerId: String)

  fun voteToThrowStar(playerId: String)
}

data class GameConnection(
  val server: Server,
  val gameId: String,
  val playerId: String,
  val player: Player,
  var state: GameState,
) : ActionReceiver, EventEmitter {
  private val listeners: MutableMap<String, () -> Unit> = mutableMapOf()

  override fun receive(action: Action) {
    when (action) {
      Action.PlayCard -> server.playCard(playerId = playerId)
      Action.StartGame -> server.startGame(playerId = playerId)
      Action.VoteToThrowStar -> server.voteToThrowStar(playerId = playerId)
    }
  }

  override fun listen(fn: () -> Unit): Listener {
    val listenerId: String = UUID.randomUUID().toString()
    listeners[listenerId] = fn
    return Listener { listeners.remove(listenerId) }
  }

  fun triggerUpdate() = listeners.values.forEach { it() }
}

data class Player(val name: String, val isHost: Boolean)

sealed interface GameState

data class InLobby(
  val allPlayers: MutableList<String>,
) : GameState

data class InGame(
  val allPlayers: MutableList<String>,
  var currentRound: Int,
  var cards: MutableList<Card>,
  var lives: Int,
  var stars: Int,
  var votingToThrowStar: Boolean,
  val playersVotingToThrowStar: MutableSet<String>,
) : GameState

data object GameWon : GameState

data object GameLost : GameState

data class Card(val value: Int)

data class GameConfig(val roundCount: Int, val startingLivesCount: Int, val startingStarsCount: Int)

sealed interface Action {
  data object StartGame : Action

  data object PlayCard : Action

  data object VoteToThrowStar : Action
}
