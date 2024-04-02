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

  override fun getConnection(playerId: String): GameConnection? =
    getGame(playerId = playerId)?.let { (_, connection) -> connection }

  override fun startGame(playerId: String) {
    val (game, _) = getGame(playerId = playerId)!!
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
    val (game, connection) = getGame(playerId = playerId)!!
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
    val (game, connection) = getGame(playerId = playerId)!!
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

  override fun leave(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    game.connections.remove(connection)
    if (connection.player.isHost) {
      if (game.connections.isNotEmpty()) {
        game.connections[0].player.isHost = true
      } else {
        games.remove(game)
      }
    }
    for (player in game.connections) {
      when (val state = player.state) {
        is GameLost -> Unit // Do nothing.
        is GameWon -> Unit // Do nothing.
        is InGame -> player.state = PlayerLeft(playerName = connection.player.name)
        is InLobby -> state.allPlayers.remove(connection.player.name)
        is PlayerLeft -> Unit // Do nothing.
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

  private fun getGame(playerId: String): Pair<InternalGame, GameConnection>? {
    for (game in games) {
      val player = game.connections.firstOrNull { it.playerId == playerId }
      if (player != null) {
        return game to player
      }
    }
    return null
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

private var GameConnection.currentRound: Int
  get() = (state as InGame).currentRound
  set(value) {
    (state as InGame).currentRound = value
  }

private var GameConnection.cards: MutableList<Card>
  get() = (state as InGame).cards
  set(value) {
    (state as InGame).cards = value
  }

private var GameConnection.lives: Int
  get() = (state as InGame).lives
  set(value) {
    (state as InGame).lives = value
  }

private var GameConnection.stars: Int
  get() = (state as InGame).stars
  set(value) {
    (state as InGame).stars = value
  }

private var GameConnection.votingToThrowStar: Boolean
  get() = (state as InGame).votingToThrowStar
  set(value) {
    (state as InGame).votingToThrowStar = value
  }

private val GameConnection.playersVotingToThrowStar: MutableSet<String>
  get() = (state as InGame).playersVotingToThrowStar
