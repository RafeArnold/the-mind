package uk.co.rafearnold.themind

import java.util.UUID

interface Server {

  fun createGame(): CreateGameResponse

  fun joinGame(gameId: String): Player

  fun startGame(player: Player)

  fun playCard(player: Player)
}

data class Player(
  val gameId: String,
  val isHost: Boolean,
  var state: ClientState = InLobby,
  var lives: Int,
)

sealed interface ClientState

data object InLobby : ClientState

data class InGame(var cards: MutableList<Card>) : ClientState

data object GameWon : ClientState

data object GameLost : ClientState

data class Card(val value: Int)

data class CreateGameResponse(val host: Player, val gameId: String)

data class GameConfig(val roundCount: Int, val startingLivesCount: Int)

class SimpleServer(private val gameConfig: GameConfig) : Server {

  private val games: MutableList<Game> = mutableListOf()

  override fun createGame(): CreateGameResponse {
    val gameId = UUID.randomUUID().toString()
    val host = Player(gameId = gameId, isHost = true, lives = gameConfig.startingLivesCount)
    val game = Game(id = gameId, players = mutableListOf(host), currentRound = 1)
    games.add(game)
    return CreateGameResponse(host = host, gameId = gameId)
  }

  override fun joinGame(gameId: String): Player {
    val game = getGame(gameId = gameId)
    val player = Player(gameId = gameId, isHost = false, lives = gameConfig.startingLivesCount)
    game.players.add(player)
    return player
  }

  override fun startGame(player: Player) {
    val game = getGame(player = player)
    val deck = shuffledDeck()
    for (@Suppress("NAME_SHADOWING") player in game.players) {
      player.state = InGame(cards = mutableListOf(Card(deck.next())))
    }
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
        return
      } else {
        for (@Suppress("NAME_SHADOWING") player in game.players) {
          player.cards.removeAll { it.value < removedCard.value }
        }
      }
    }
    if (game.players.all { it.cards.isEmpty() }) {
      // Round complete.
      if (game.currentRound == gameConfig.roundCount) {
        game.setState(GameWon)
      } else {
        game.currentRound++
        val deck = shuffledDeck()
        for (@Suppress("NAME_SHADOWING") player in game.players) {
          player.cards = (1..game.currentRound).map { Card(deck.next()) }.toMutableList()
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
}

private data class Game(val id: String, val players: MutableList<Player>, var currentRound: Int)

private var Player.cards: MutableList<Card>
  get() = (state as InGame).cards
  set(cards) {
    (state as InGame).cards = cards
  }
