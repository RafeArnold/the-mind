package uk.co.rafearnold.mind

import java.util.UUID

interface Server {

  fun createLobby(): CreateLobbyResponse

  fun joinLobby(lobbyId: String): Player

  fun startGame(player: Player)

  fun playCard(player: Player)
}

data class Player(val lobbyId: String, val isHost: Boolean, var state: ClientState = InLobby)

sealed interface ClientState

data object InLobby : ClientState

data class InGame(val cards: MutableList<Card>) : ClientState

data object GameWon : ClientState

data object GameLost : ClientState

data class Card(val value: Int)

data class CreateLobbyResponse(val host: Player, val lobbyId: String)

class SimpleServer : Server {

  private val games: MutableList<Game> = mutableListOf()

  override fun createLobby(): CreateLobbyResponse {
    val lobbyId = UUID.randomUUID().toString()
    val host = Player(lobbyId = lobbyId, isHost = true)
    val game = Game(id = lobbyId, players = mutableListOf(host))
    games.add(game)
    return CreateLobbyResponse(host = host, lobbyId = lobbyId)
  }

  override fun joinLobby(lobbyId: String): Player {
    val lobby = getGame(lobbyId = lobbyId)
    val player = Player(lobbyId = lobbyId, isHost = false)
    lobby.players.add(player)
    return player
  }

  override fun startGame(player: Player) {
    val game = getGame(player = player)
    val deck = (1..100).shuffled().iterator()
    for (@Suppress("NAME_SHADOWING") player in game.players) {
      player.state = InGame(cards = mutableListOf(Card(deck.next())))
    }
  }

  override fun playCard(player: Player) {
    val game = getGame(player = player)
    val removedCard = (player.state as InGame).cards.removeFirst()
    if (game.players.all { (it.state as InGame).cards.isEmpty() }) {
      game.setState(GameWon)
    } else if (removedCard.value >
      game.players.flatMap { (it.state as InGame).cards.map { card -> card.value } }.min()
    ) {
      game.setState(GameLost)
    }
  }

  private fun Game.setState(state: ClientState) {
    for (player in players) {
      player.state = state
    }
  }

  private fun getGame(player: Player): Game = getGame(lobbyId = player.lobbyId)

  private fun getGame(lobbyId: String): Game = games.first { it.id == lobbyId }
}

private data class Game(val id: String, val players: MutableList<Player>)
