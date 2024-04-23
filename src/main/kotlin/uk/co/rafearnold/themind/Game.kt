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

  fun ready(playerId: String)

  fun unready(playerId: String)

  fun playCard(playerId: String)

  fun voteToThrowStar(playerId: String)

  fun revokeVoteToThrowStar(playerId: String)

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
      Action.Ready -> server.ready(playerId = player.id)
      Action.Unready -> server.unready(playerId = player.id)
      Action.VoteToThrowStar -> server.voteToThrowStar(playerId = player.id)
      Action.RevokeVoteToThrowStar -> server.revokeVoteToThrowStar(playerId = player.id)
    }
  }

  override fun listen(fn: () -> Unit): Listener {
    val listenerId: String = UUID.randomUUID().toString()
    listeners[listenerId] = fn
    return Listener { listeners.remove(listenerId) }
  }

  fun triggerUpdate() = listeners.values.forEach { it() }
}

data class Player(val id: String, val name: String, var isReady: Boolean)

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
  var roundCount: Int,
  var cards: MutableList<Card>,
  var lives: Int,
  var stars: Int,
  var isVotingToThrowStar: Boolean,
  var playedCards: MutableList<Card>,
  var levelReward: LevelReward,
) : GameState

enum class LevelReward {
  NONE,
  LIFE,
  STAR,
}

data object GameWon : GameState

data object GameLost : GameState

data class PlayerLeft(val playerName: String) : GameState

data class Card(val value: Int)

data class GameConfig(
  val roundCount: (InLobby) -> Int = {
    when (it.allPlayers.size) {
      1, 2 -> 12
      3 -> 10
      else -> 8
    }
  },
  val startingStarsCount: Int = 1,
  val startingLivesCount: (InLobby) -> Int = { it.allPlayers.size },
)

sealed interface Action {
  data object Ready : Action

  data object Unready : Action

  data object PlayCard : Action

  data object VoteToThrowStar : Action

  data object RevokeVoteToThrowStar : Action
}
