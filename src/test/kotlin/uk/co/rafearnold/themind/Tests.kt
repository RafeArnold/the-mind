package uk.co.rafearnold.themind

import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Tests {

  @Test
  fun `all cards played in correct order`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0),
      )
    val host = server.createGame()
    val gameId = host.gameId
    assertEquals(InLobby(allPlayers = mutableListOf(host.player)), host.state)
    val player2 = server.joinGame(gameId = gameId)
    assertEquals(InLobby(allPlayers = mutableListOf(host.player, player2.player)), host.state)
    assertEquals(InLobby(allPlayers = mutableListOf(host.player, player2.player)), player2.state)
    val player3 = server.joinGame(gameId = gameId)
    val allPlayers = mutableListOf(host.player, player2.player, player3.player)
    assertEquals(InLobby(allPlayers = allPlayers), host.state)
    assertEquals(InLobby(allPlayers = allPlayers), player2.state)
    assertEquals(InLobby(allPlayers = allPlayers), player3.state)
    server.startGame(host)
    host.assertInGameWithOneCard()
    player2.assertInGameWithOneCard()
    player3.assertInGameWithOneCard()
    val players = mutableListOf(host, player2, player3)
    players.assertNoDuplicateCards()
    var nextPlayer = players.nextPlayer()
    server.playCard(nextPlayer)
    nextPlayer.assertInGameWithNoCards()
    players.remove(nextPlayer)
    players.forEach { it.assertInGameWithOneCard() }
    nextPlayer = players.nextPlayer()
    server.playCard(nextPlayer)
    nextPlayer.assertInGameWithNoCards()
    players.remove(nextPlayer)
    players.forEach { it.assertInGameWithOneCard() }
    server.playCard(players[0])
    assertEquals(GameWon, host.state)
    assertEquals(GameWon, player2.state)
    assertEquals(GameWon, player3.state)
  }

  @Test
  fun `card played in wrong order`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)
    val players = mutableListOf(host, player2, player3)
    val firstPlayer = players.nextPlayer()
    server.playCard(firstPlayer)
    val incorrectNextPlayer = players.first { it != firstPlayer && it != players.nextPlayer() }
    server.playCard(incorrectNextPlayer)
    assertEquals(GameLost, host.state)
    assertEquals(GameLost, player2.state)
    assertEquals(GameLost, player3.state)
  }

  @Test
  fun `all cards played in correct order with multiple rounds`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)
    val allPlayers = listOf(host, player2, player3)
    allPlayers.forEach { it.assertInGameWithNCards(1) }
    for (i in 1..3) {
      server.playCard(allPlayers.nextPlayer())
    }
    allPlayers.forEach { it.assertInGameWithNCards(2) }
    for (i in 1..6) {
      server.playCard(allPlayers.nextPlayer())
    }
    allPlayers.forEach { it.assertInGameWithNCards(3) }
    for (i in 1..9) {
      server.playCard(allPlayers.nextPlayer())
    }
    allPlayers.forEach { assertEquals(GameWon, it.state) }
  }

  @Test
  fun `cards played in wrong order with multiple rounds and multiple lives`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 3, startingLivesCount = 3, startingStarsCount = 0),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)
    val allPlayers = mutableListOf(host, player2, player3)
    allPlayers.forEach { assertEquals(3, it.lives) }

    allPlayers[0].cards = mutableListOf(Card(value = 73))
    allPlayers[1].cards = mutableListOf(Card(value = 82))
    allPlayers[2].cards = mutableListOf(Card(value = 84))

    server.playCard(allPlayers.nextPlayer())

    server.playCard(allPlayers[2])
    allPlayers.forEach { assertEquals(2, it.lives) }

    allPlayers.forEach { it.assertInGameWithNCards(2) }
    allPlayers[0].cards = mutableListOf(Card(value = 25), Card(value = 64))
    allPlayers[1].cards = mutableListOf(Card(value = 53), Card(value = 69))
    allPlayers[2].cards = mutableListOf(Card(value = 63), Card(value = 77))

    server.playCard(allPlayers[2])
    allPlayers.forEach { assertEquals(1, it.lives) }
    assertEquals(mutableListOf(Card(value = 64)), allPlayers[0].cards)
    assertEquals(mutableListOf(Card(value = 69)), allPlayers[1].cards)
    assertEquals(mutableListOf(Card(value = 77)), allPlayers[2].cards)

    server.playCard(allPlayers[1])
    allPlayers.forEach { assertEquals(GameLost, it.state) }
  }

  @Test
  fun `star thrown`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 2, startingLivesCount = 1, startingStarsCount = 2),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)

    val allPlayers = mutableListOf(host, player2, player3)
    allPlayers.sortByMinCardValue()
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers.forEach { assertFalse(it.isVotingToThrowStar) }

    server.playCard(allPlayers[0])

    server.voteToThrowStar(player2)
    assertTrue(player2.isVotingToThrowStar)
    assertFalse(host.isVotingToThrowStar)
    assertFalse(player3.isVotingToThrowStar)
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers[0].assertInGameWithNCards(0)
    allPlayers[1].assertInGameWithNCards(1)
    allPlayers[2].assertInGameWithNCards(1)

    server.voteToThrowStar(host)
    assertTrue(player2.isVotingToThrowStar)
    assertTrue(host.isVotingToThrowStar)
    assertFalse(player3.isVotingToThrowStar)
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers[0].assertInGameWithNCards(0)
    allPlayers[1].assertInGameWithNCards(1)
    allPlayers[2].assertInGameWithNCards(1)

    server.voteToThrowStar(player3)
    allPlayers.forEach { it.assertInGameWithNCards(2) }
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { assertFalse(it.isVotingToThrowStar) }

    allPlayers[0].cards = mutableListOf(Card(value = 11), Card(value = 50))
    allPlayers[1].cards = mutableListOf(Card(value = 33), Card(value = 88))
    allPlayers[2].cards = mutableListOf(Card(value = 17), Card(value = 90))

    server.playCard(allPlayers[0])
    server.playCard(allPlayers[2])
    server.playCard(allPlayers[1])

    server.voteToThrowStar(allPlayers[0])
    assertTrue(allPlayers[0].isVotingToThrowStar)
    assertFalse(allPlayers[1].isVotingToThrowStar)
    assertFalse(allPlayers[2].isVotingToThrowStar)
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(1) }

    server.voteToThrowStar(allPlayers[1])
    assertTrue(allPlayers[0].isVotingToThrowStar)
    assertTrue(allPlayers[1].isVotingToThrowStar)
    assertFalse(allPlayers[2].isVotingToThrowStar)
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(1) }

    server.voteToThrowStar(allPlayers[2])
    allPlayers.forEach { assertEquals(GameWon, it.state) }
  }

  @Test
  fun `playing a card resets votes to throw star`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 2, startingLivesCount = 1, startingStarsCount = 1),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)

    val allPlayers = mutableListOf(host, player2, player3)
    allPlayers.sortByMinCardValue()

    server.voteToThrowStar(allPlayers[0])
    server.voteToThrowStar(allPlayers[1])

    server.playCard(allPlayers[0])
    allPlayers.forEach { assertFalse(it.isVotingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }

    server.voteToThrowStar(allPlayers[0])

    server.playCard(allPlayers[1])
    allPlayers.forEach { assertFalse(it.isVotingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }

    server.voteToThrowStar(allPlayers[0])
    server.voteToThrowStar(allPlayers[2])

    server.playCard(allPlayers[2])
    allPlayers.forEach { assertFalse(it.isVotingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(2) }
  }

  @Test
  fun `leave lobby`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 1),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)

    assertEquals(listOf(host.player, player2.player, player3.player), host.lobbyState.allPlayers)

    server.leave(player3)
    assertEquals(listOf(host.player, player2.player), host.lobbyState.allPlayers)

    server.startGame(host)

    server.playCard(listOf(host, player2).nextPlayer())
    server.playCard(listOf(host, player2).nextPlayer())
    assertEquals(GameWon, host.state)
    assertEquals(GameWon, player2.state)
  }

  @Test
  fun `leave game`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 1),
      )
    val host = server.createGame()
    val gameId = host.gameId
    val player2 = server.joinGame(gameId = gameId)
    val player3 = server.joinGame(gameId = gameId)
    server.startGame(host)

    assertEquals(
      listOf(
        OtherPlayer(
          id = player2.player.id,
          name = player2.player.name,
          isVotingToThrowStar = player2.isVotingToThrowStar,
          cardCount = player2.cards.size,
        ),
        OtherPlayer(
          id = player3.player.id,
          name = player3.player.name,
          isVotingToThrowStar = player3.isVotingToThrowStar,
          cardCount = player3.cards.size,
        ),
      ),
      host.inGameState.otherPlayers,
    )

    server.leave(player3)

    assertEquals(PlayerLeft(playerName = player3.player.name), host.state)
    assertEquals(PlayerLeft(playerName = player3.player.name), player2.state)
  }

  @Test
  fun `game ids are a reasonable format`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 1),
      )
    repeat(10) { assertTrue(server.createGame().gameId.matches(Regex("[A-Z0-9]{2}"))) }
  }

  @Test
  fun `game id is case insensitive when joining game`() {
    val server =
      InMemoryServer(
        gameConfig = gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0),
      )

    val host = server.createGame()
    val player2 = server.joinGame(gameId = host.gameId.uppercase())
    val player3 = server.joinGame(gameId = host.gameId.lowercase())

    assertEquals(
      listOf(host.player.name, player2.player.name, player3.player.name),
      host.lobbyState.allPlayers.map { it.name },
    )
  }

  @Test
  fun `starting lives are determined by player count`() {
    val server = InMemoryServer()

    repeat(99) {
      val playerCount = it + 2
      val host = server.createGame()
      val otherPlayers = List(playerCount - 1) { server.joinGame(gameId = host.gameId) }

      server.startGame(host)

      (otherPlayers + host).forEach { player -> assertEquals(playerCount, player.lives) }
    }
  }
}

private fun gameConfig(
  roundCount: Int,
  startingLivesCount: Int,
  startingStarsCount: Int,
): GameConfig =
  GameConfig(roundCount = roundCount, startingStarsCount = startingStarsCount) {
    startingLivesCount
  }

private fun Server.createGame(): GameConnection =
  createGame(playerName = UUID.randomUUID().toString())

private fun Server.joinGame(gameId: String): GameConnection =
  joinGame(gameId = gameId, playerName = UUID.randomUUID().toString())

private fun Server.startGame(connection: GameConnection) =
  startGame(playerId = connection.player.id)

private fun Server.playCard(connection: GameConnection) = playCard(playerId = connection.player.id)

private fun Server.voteToThrowStar(connection: GameConnection) =
  voteToThrowStar(playerId = connection.player.id)

private fun Server.leave(connection: GameConnection) = leave(playerId = connection.player.id)

private fun List<GameConnection>.nextPlayer(): GameConnection = minByOrNull { it.minCardValue() }!!

private fun MutableList<GameConnection>.sortByMinCardValue() = sortBy { it.minCardValue() }

private fun GameConnection.minCardValue(): Int =
  cards.minOfOrNull { card -> card.value } ?: Int.MAX_VALUE

private fun GameConnection.assertInGameWithOneCard() {
  this.assertInGameWithNCards(1)
}

private fun GameConnection.assertInGameWithNoCards() {
  this.assertInGameWithNCards(0)
}

private fun GameConnection.assertInGameWithNCards(n: Int) {
  assertIs<InGame>(state)
  assertEquals(n, cards.size)
  for (i in 0 until n) {
    val card = cards[i]
    assertTrue(card.value >= 1)
    assertTrue(card.value <= 100)
  }
}

private fun List<GameConnection>.assertNoDuplicateCards() {
  val allCards = flatMap { it.cards.map { card -> card.value } }
  assertEquals(allCards, allCards.distinct())
}

private val GameConnection.lobbyState: InLobby get() = state as InLobby

private val GameConnection.inGameState: InGame get() = state as InGame

private var GameConnection.cards: MutableList<Card>
  get() = (state as InGame).cards
  set(cards) {
    (state as InGame).cards = cards
  }

private val GameConnection.lives: Int
  get() = (state as InGame).lives

private val GameConnection.stars: Int
  get() = (state as InGame).stars

private val GameConnection.isVotingToThrowStar: Boolean
  get() = (state as InGame).isVotingToThrowStar

class TestSupportTests {

  @Test
  fun `retrieves correct next player`() {
    val player1 = createInGamePlayer(1, 6)
    val player2 = createInGamePlayer(2, 9)
    val player3 = createInGamePlayer(3, 7, 11)
    val player4 = createInGamePlayer(4, 8)
    val player5 = createInGamePlayer(5, 10)
    val players = mutableListOf(player1, player4, player2, player5, player3)
    players.assertNextPlayerEqualsAndRemoveCard(player1)
    players.assertNextPlayerEqualsAndRemoveCard(player2)
    players.assertNextPlayerEqualsAndRemoveCard(player3)
    players.assertNextPlayerEqualsAndRemoveCard(player4)
    players.assertNextPlayerEqualsAndRemoveCard(player5)
    players.assertNextPlayerEqualsAndRemoveCard(player1)
    players.assertNextPlayerEqualsAndRemoveCard(player3)
    players.assertNextPlayerEqualsAndRemoveCard(player4)
    players.assertNextPlayerEqualsAndRemoveCard(player2)
    players.assertNextPlayerEqualsAndRemoveCard(player5)
    players.assertNextPlayerEqualsAndRemoveCard(player3)
  }

  private fun MutableList<GameConnection>.assertNextPlayerEqualsAndRemoveCard(
    expected: GameConnection,
  ) {
    val nextPlayer = nextPlayer()
    assertEquals(expected, nextPlayer)
    nextPlayer.cards.apply { remove(minByOrNull { it.value }) }
  }

  private fun createInGamePlayer(vararg cardValues: Int): GameConnection =
    GameConnection(
      server = InMemoryServer(),
      gameId = UUID.randomUUID().toString(),
      player =
        Player(
          id = UUID.randomUUID().toString(),
          name = UUID.randomUUID().toString(),
          isHost = Random.nextBoolean(),
        ),
      state =
        InGame(
          otherPlayers = mutableListOf(),
          currentRound = 1,
          cards = cardValues.map { Card(value = it) }.shuffled().toMutableList(),
          lives = Random.nextInt(1, 3),
          stars = Random.nextInt(0, 2),
          isVotingToThrowStar = Random.nextBoolean(),
          playedCards = mutableListOf(),
        ),
    )
}
