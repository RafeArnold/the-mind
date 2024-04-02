package uk.co.rafearnold.themind

import java.util.UUID

class InMemoryServer(private val gameConfig: GameConfig) : Server {

  private val games: MutableList<InternalGame> = mutableListOf()

  override fun createGame(playerName: String): GameConnection {
    val gameId = UUID.randomUUID().toString()
    val playerId = UUID.randomUUID().toString()
    val host =
      GameConnection(
        server = this,
        gameId = gameId,
        playerId = playerId,
        player = Player(name = playerName, isHost = true),
        state = InLobby(allPlayers = mutableListOf(playerName)),
      )
    val game = InternalGame(id = gameId, connections = mutableListOf(host))
    games.add(game)
    return host
  }

  override fun joinGame(
    gameId: String,
    playerName: String,
  ): GameConnection {
    val game = get(gameId = gameId)
    val allPlayers = game.connections
    val playerId = UUID.randomUUID().toString()
    val player =
      GameConnection(
        server = this,
        gameId = gameId,
        playerId = playerId,
        player = Player(name = playerName, isHost = false),
        state = InLobby(allPlayers = allPlayers.map { it.player.name }.toMutableList()),
      )
    allPlayers.add(player)
    allPlayers.forEach { (it.state as InLobby).allPlayers.add(playerName) }
    game.triggerUpdate()
    return player
  }

  override fun connect(playerId: String): GameConnection {
    val (_, connection) = getGame(playerId = playerId)
    return connection
  }

  override fun startGame(playerId: String) {
    val (game, _) = getGame(playerId = playerId)
    val deck = shuffledDeck()
    for (player in game.connections) {
      player.state =
        InGame(
          allPlayers = (player.state as InLobby).allPlayers,
          currentRound = 1,
          cards = mutableListOf(Card(deck.next())),
          lives = gameConfig.startingLivesCount,
          stars = gameConfig.startingStarsCount,
          votingToThrowStar = false,
          playersVotingToThrowStar = mutableSetOf(),
        )
    }
    game.triggerUpdate()
  }

  override fun playCard(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)
    val cards = connection.cards
    val removedCard = cards.minByOrNull { it.value }!!
    cards.remove(removedCard)
    val lowestCardValue =
      game.connections.flatMap { it.cards.map { card -> card.value } }.minOrNull() ?: Int.MAX_VALUE
    if (removedCard.value > lowestCardValue) {
      // A mistake was made.
      game.connections.forEach { it.lives-- }
      if (game.connections.first().lives == 0) {
        game.setState(GameLost)
        game.triggerUpdate()
        return
      } else {
        for (player in game.connections) {
          player.cards.removeAll { it.value < removedCard.value }
        }
      }
    }
    game.connections.forEach {
      it.votingToThrowStar = false
      it.playersVotingToThrowStar.clear()
    }
    game.handlePossibleRoundComplete()
    game.triggerUpdate()
  }

  override fun voteToThrowStar(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)
    connection.votingToThrowStar = true
    if (game.connections.all { it.votingToThrowStar }) {
      for (player in game.connections) {
        player.stars--
        player.cards.apply { remove(minByOrNull { it.value }) }
        player.votingToThrowStar = false
        player.playersVotingToThrowStar.clear()
      }
      game.handlePossibleRoundComplete()
    } else {
      for (otherPlayer in game.connections) {
        otherPlayer.playersVotingToThrowStar.add(connection.player.name)
      }
    }
    game.triggerUpdate()
  }

  private fun InternalGame.handlePossibleRoundComplete() {
    if (connections.all { it.cards.isEmpty() }) {
      // Round complete.
      val currentRound = connections.first().currentRound
      if (currentRound == gameConfig.roundCount) {
        setState(GameWon)
      } else {
        val nextRound = currentRound + 1
        connections.forEach { it.currentRound = nextRound }
        val deck = shuffledDeck()
        for (player in connections) {
          player.cards = (1..nextRound).map { Card(deck.next()) }.toMutableList()
        }
      }
    }
  }

  private fun InternalGame.setState(state: GameState) {
    for (player in connections) {
      player.state = state
    }
  }

  private fun getGame(playerId: String): Pair<InternalGame, GameConnection> {
    for (game in games) {
      val player = game.connections.firstOrNull { it.playerId == playerId }
      if (player != null) {
        return game to player
      }
    }
    throw NoSuchElementException("Player with id $playerId doesn't exist")
  }

  private fun get(gameId: String): InternalGame = games.first { it.id == gameId }

  private fun shuffledDeck(): Iterator<Int> = (1..100).shuffled().iterator()

  private fun InternalGame.triggerUpdate() {
    connections.forEach { it.triggerUpdate() }
  }
}

private data class InternalGame(
  val id: String,
  val connections: MutableList<GameConnection>,
)
