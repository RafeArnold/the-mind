package uk.co.rafearnold.themind

import java.util.UUID

class InMemoryServer(
  private val gameConfig: GameConfig = GameConfig(),
  private val gameIdGenerator: GameIdGenerator = SqidsGameIdGenerator(),
) : Server {

  private val games: MutableList<InternalGame> = mutableListOf()

  override fun createGame(playerName: String): GameConnection {
    val gameId = gameIdGenerator.nextId()
    val playerId = UUID.randomUUID().toString()
    val player = Player(id = playerId, name = playerName, isReady = false)
    val connection =
      GameConnection(
        server = this,
        gameId = gameId,
        player = player,
        state = InLobby(allPlayers = mutableListOf(player)),
      )
    val game = InternalGame(id = gameId, connections = mutableListOf(connection))
    games.add(game)
    return connection
  }

  override fun joinGame(
    gameId: String,
    playerName: String,
  ): GameConnection {
    val game = get(gameId = gameId)
    val allPlayers = game.connections
    val playerId = UUID.randomUUID().toString()
    val player = Player(id = playerId, name = playerName, isReady = false)
    val connection =
      GameConnection(
        server = this,
        gameId = game.id,
        player = player,
        state = InLobby(allPlayers = allPlayers.map { it.player }.toMutableList()),
      )
    allPlayers.add(connection)
    allPlayers.forEach { (it.state as InLobby).allPlayers.add(player) }
    game.triggerUpdate()
    return connection
  }

  override fun getConnection(playerId: String): GameConnection? =
    getGame(playerId = playerId)?.let { (_, connection) -> connection }

  override fun ready(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    connection.player.isReady = true
    for (player in game.connections) {
      (player.state as InLobby).allPlayers.first { it.id == connection.player.id }.isReady = true
    }
    game.handlePossibleGameStart()
    game.triggerUpdate()
  }

  override fun unready(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    connection.player.isReady = false
    for (player in game.connections) {
      (player.state as InLobby).allPlayers.first { it.id == connection.player.id }.isReady = false
    }
    game.triggerUpdate()
  }

  override fun playCard(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    val cards = connection.cards
    val removedCard = cards.minByOrNull { it.value }!!
    cards.remove(removedCard)
    val removedCards: MutableList<Card> = mutableListOf()
    removedCards.add(removedCard)
    val lowestCardValue =
      game.connections.flatMap { it.cards.map { card -> card.value } }.minOrNull() ?: Int.MAX_VALUE
    val playerCardsCounts =
      connection.otherPlayers.associate { it.id to it.cardCount }.toMutableMap()
    playerCardsCounts[connection.player.id] = cards.size
    if (removedCard.value > lowestCardValue) {
      // A mistake was made.
      game.connections.forEach { it.lives-- }
      if (game.connections.first().lives == 0) {
        game.setState(GameLost)
        game.triggerUpdate()
        return
      } else {
        for (player in game.connections) {
          val cardsToRemove = player.cards.filter { it.value < removedCard.value }
          player.cards.removeAll(cardsToRemove)
          playerCardsCounts[player.player.id] =
            playerCardsCounts[player.player.id]!! - cardsToRemove.size
          removedCards.addAll(cardsToRemove)
        }
      }
    }
    removedCards.sortBy { it.value }
    game.connections.forEach {
      it.playedCards.addAll(removedCards)
      it.resetVotes()
      for ((otherPlayerId, count) in playerCardsCounts
        .filter { (otherPlayerId, _) -> it.player.id != otherPlayerId }) {
        it.otherPlayers.first { other -> other.id == otherPlayerId }.cardCount = count
      }
    }
    game.handlePossibleRoundComplete()
    game.triggerUpdate()
  }

  override fun voteToThrowStar(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    connection.isVotingToThrowStar = true
    if (game.connections.all { it.isVotingToThrowStar }) {
      val removedCards: MutableList<Card> = mutableListOf()
      for (player in game.connections) {
        player.stars--
        val minCard = player.cards.minByOrNull { card -> card.value }
        if (minCard != null) {
          player.cards.remove(minCard)
          removedCards.add(minCard)
        }
        player.resetVotes()
        player.otherPlayers.forEach { it.cardCount = (it.cardCount - 1).coerceAtLeast(0) }
      }
      if (removedCards.isNotEmpty()) {
        removedCards.sortBy { it.value }
        for (player in game.connections) {
          player.playedCards.addAll((player.playedCards.size - 2).coerceAtLeast(0), removedCards)
        }
      }
      game.handlePossibleRoundComplete()
    } else {
      for (other in game.connections.filter { it.player.id != connection.player.id }) {
        other.otherPlayers.first { it.id == connection.player.id }.isVotingToThrowStar = true
      }
    }
    game.triggerUpdate()
  }

  override fun revokeVoteToThrowStar(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    connection.isVotingToThrowStar = false
    for (other in game.connections.filter { it.player.id != connection.player.id }) {
      other.otherPlayers.first { it.id == connection.player.id }.isVotingToThrowStar = false
    }
    game.triggerUpdate()
  }

  override fun leave(playerId: String) {
    val (game, connection) = getGame(playerId = playerId)!!
    game.connections.remove(connection)
    if (game.connections.isEmpty()) {
      games.remove(game)
    }
    for (player in game.connections) {
      when (val state = player.state) {
        is GameLost -> Unit // Do nothing.
        is GameWon -> Unit // Do nothing.
        is InGame -> player.state = PlayerLeft(playerName = connection.player.name)
        is InLobby -> state.allPlayers.removeIf { it.id == connection.player.id }
        is PlayerLeft -> Unit // Do nothing.
      }
    }
    if (connection.state is InLobby) {
      game.handlePossibleGameStart()
    }
    game.triggerUpdate()
  }

  private fun InternalGame.handlePossibleRoundComplete() {
    if (connections.all { it.cards.isEmpty() }) {
      // Round complete.
      val currentRound = connections.first().currentRound
      if (currentRound == connections.first().roundCount) {
        setState(GameWon)
      } else {
        val nextRound = currentRound + 1
        val deck = shuffledDeck()
        for (player in connections) {
          player.currentRound = nextRound
          player.cards = (1..nextRound).map { Card(deck.next()) }.toMutableList()
          player.otherPlayers.forEach { it.cardCount = nextRound }
          player.playedCards = mutableListOf()
          when (currentRound) {
            2, 5, 8 -> player.stars++
            3, 6, 9 -> player.lives++
          }
          when (nextRound) {
            2, 5, 8 -> player.levelReward = LevelReward.STAR
            3, 6, 9 -> player.levelReward = LevelReward.LIFE
            else -> player.levelReward = LevelReward.NONE
          }
        }
      }
    }
  }

  private fun InternalGame.handlePossibleGameStart() {
    if (connections.size > 1 && connections.all { it.player.isReady }) {
      // Everyone is ready, so starting the game.
      val deck = shuffledDeck()
      for (player in connections) {
        val inLobbyState = player.state as InLobby
        player.state =
          InGame(
            otherPlayers =
              inLobbyState.allPlayers
                .filter { it.id != player.player.id }
                .map {
                  OtherPlayer(
                    id = it.id,
                    name = it.name,
                    isVotingToThrowStar = false,
                    cardCount = 1,
                  )
                }
                .toMutableList(),
            currentRound = 1,
            roundCount = gameConfig.roundCount(inLobbyState),
            cards = mutableListOf(Card(deck.next())),
            lives = gameConfig.startingLivesCount(inLobbyState),
            stars = gameConfig.startingStarsCount,
            isVotingToThrowStar = false,
            playedCards = mutableListOf(),
            levelReward = LevelReward.NONE,
          )
      }
    }
  }

  private fun GameConnection.resetVotes() {
    isVotingToThrowStar = false
    otherPlayers.forEach { it.isVotingToThrowStar = false }
  }

  private fun InternalGame.setState(state: GameState) {
    for (player in connections) {
      player.state = state
    }
  }

  private fun getGame(playerId: String): Pair<InternalGame, GameConnection>? {
    for (game in games) {
      val player = game.connections.firstOrNull { it.player.id == playerId }
      if (player != null) {
        return game to player
      }
    }
    return null
  }

  private fun get(gameId: String): InternalGame =
    games.first { it.id.equals(gameId, ignoreCase = true) }

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

private val GameConnection.roundCount: Int
  get() = (state as InGame).roundCount

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

private var GameConnection.isVotingToThrowStar: Boolean
  get() = (state as InGame).isVotingToThrowStar
  set(value) {
    (state as InGame).isVotingToThrowStar = value
  }

private val GameConnection.otherPlayers: List<OtherPlayer>
  get() = (state as InGame).otherPlayers

private var GameConnection.playedCards: MutableList<Card>
  get() = (state as InGame).playedCards
  set(value) {
    (state as InGame).playedCards = value
  }

private var GameConnection.levelReward: LevelReward
  get() = (state as InGame).levelReward
  set(value) {
    (state as InGame).levelReward = value
  }
