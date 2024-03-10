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
      SimpleServer(
        gameConfig = GameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0),
      )
    val (host, gameId) = server.createGame()
    assertEquals(InLobby, host.state)
    val player2 = server.joinGame(gameId)
    assertEquals(InLobby, player2.state)
    val player3 = server.joinGame(gameId)
    assertEquals(InLobby, player3.state)
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
      SimpleServer(
        gameConfig = GameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0),
      )
    val (host, gameId) = server.createGame()
    val player2 = server.joinGame(gameId)
    val player3 = server.joinGame(gameId)
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
      SimpleServer(
        gameConfig = GameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0),
      )
    val (host, gameId) = server.createGame()
    val player2 = server.joinGame(gameId)
    val player3 = server.joinGame(gameId)
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
      SimpleServer(
        gameConfig = GameConfig(roundCount = 3, startingLivesCount = 3, startingStarsCount = 0),
      )
    val (host, gameId) = server.createGame()
    val player2 = server.joinGame(gameId)
    val player3 = server.joinGame(gameId)
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
      SimpleServer(
        gameConfig = GameConfig(roundCount = 2, startingLivesCount = 1, startingStarsCount = 2),
      )
    val (host, gameId) = server.createGame()
    val player2 = server.joinGame(gameId)
    val player3 = server.joinGame(gameId)
    server.startGame(host)

    val allPlayers = mutableListOf(host, player2, player3)
    allPlayers.sortByMinCardValue()
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers.forEach { assertFalse(it.votingToThrowStar) }

    server.playCard(allPlayers[0])

    server.voteToThrowStar(player2)
    assertTrue(player2.votingToThrowStar)
    assertFalse(host.votingToThrowStar)
    assertFalse(player3.votingToThrowStar)
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers[0].assertInGameWithNCards(0)
    allPlayers[1].assertInGameWithNCards(1)
    allPlayers[2].assertInGameWithNCards(1)

    server.voteToThrowStar(host)
    assertTrue(player2.votingToThrowStar)
    assertTrue(host.votingToThrowStar)
    assertFalse(player3.votingToThrowStar)
    allPlayers.forEach { assertEquals(2, it.stars) }
    allPlayers[0].assertInGameWithNCards(0)
    allPlayers[1].assertInGameWithNCards(1)
    allPlayers[2].assertInGameWithNCards(1)

    server.voteToThrowStar(player3)
    allPlayers.forEach { it.assertInGameWithNCards(2) }
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { assertFalse(it.votingToThrowStar) }

    allPlayers[0].cards = mutableListOf(Card(value = 11), Card(value = 50))
    allPlayers[1].cards = mutableListOf(Card(value = 33), Card(value = 88))
    allPlayers[2].cards = mutableListOf(Card(value = 17), Card(value = 90))

    server.playCard(allPlayers[0])
    server.playCard(allPlayers[2])
    server.playCard(allPlayers[1])

    server.voteToThrowStar(allPlayers[0])
    assertTrue(allPlayers[0].votingToThrowStar)
    assertFalse(allPlayers[1].votingToThrowStar)
    assertFalse(allPlayers[2].votingToThrowStar)
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(1) }

    server.voteToThrowStar(allPlayers[1])
    assertTrue(allPlayers[0].votingToThrowStar)
    assertTrue(allPlayers[1].votingToThrowStar)
    assertFalse(allPlayers[2].votingToThrowStar)
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(1) }

    server.voteToThrowStar(allPlayers[2])
    allPlayers.forEach { assertEquals(GameWon, it.state) }
  }

  @Test
  fun `playing a card resets votes to throw star`() {
    val server =
      SimpleServer(
        gameConfig = GameConfig(roundCount = 2, startingLivesCount = 1, startingStarsCount = 1),
      )
    val (host, gameId) = server.createGame()
    val player2 = server.joinGame(gameId)
    val player3 = server.joinGame(gameId)
    server.startGame(host)

    val allPlayers = mutableListOf(host, player2, player3)
    allPlayers.sortByMinCardValue()

    server.voteToThrowStar(allPlayers[0])
    server.voteToThrowStar(allPlayers[1])

    server.playCard(allPlayers[0])
    allPlayers.forEach { assertFalse(it.votingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }

    server.voteToThrowStar(allPlayers[0])

    server.playCard(allPlayers[1])
    allPlayers.forEach { assertFalse(it.votingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }

    server.voteToThrowStar(allPlayers[0])
    server.voteToThrowStar(allPlayers[2])

    server.playCard(allPlayers[2])
    allPlayers.forEach { assertFalse(it.votingToThrowStar) }
    allPlayers.forEach { assertEquals(1, it.stars) }
    allPlayers.forEach { it.assertInGameWithNCards(2) }
  }
}

private fun List<Player>.nextPlayer(): Player = minByOrNull { it.minCardValue() }!!

private fun MutableList<Player>.sortByMinCardValue() = sortBy { it.minCardValue() }

private fun Player.minCardValue(): Int = cards.minOfOrNull { card -> card.value } ?: Int.MAX_VALUE

private fun Player.assertInGameWithOneCard() {
  this.assertInGameWithNCards(1)
}

private fun Player.assertInGameWithNoCards() {
  this.assertInGameWithNCards(0)
}

private fun Player.assertInGameWithNCards(n: Int) {
  assertIs<InGame>(state)
  assertEquals(n, cards.size)
  for (i in 0 until n) {
    val card = cards[i]
    assertTrue(card.value >= 1)
    assertTrue(card.value <= 100)
  }
}

private fun List<Player>.assertNoDuplicateCards() {
  val allCards = flatMap { it.cards.map { card -> card.value } }
  assertEquals(allCards, allCards.distinct())
}

private var Player.cards: MutableList<Card>
  get() = (state as InGame).cards
  set(cards) {
    (state as InGame).cards = cards
  }

private val Player.lives: Int
  get() = (state as InGame).lives

private val Player.stars: Int
  get() = (state as InGame).stars

private val Player.votingToThrowStar: Boolean
  get() = (state as InGame).votingToThrowStar

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

  private fun MutableList<Player>.assertNextPlayerEqualsAndRemoveCard(expected: Player) {
    val nextPlayer = nextPlayer()
    assertEquals(expected, nextPlayer)
    nextPlayer.cards.apply { remove(minByOrNull { it.value }) }
  }

  private fun createInGamePlayer(vararg cardValues: Int): Player =
    Player(
      gameId = UUID.randomUUID().toString(),
      isHost = Random.nextBoolean(),
      state =
        InGame(
          cards = cardValues.map { Card(value = it) }.shuffled().toMutableList(),
          lives = Random.nextInt(1, 3),
          stars = Random.nextInt(0, 2),
          votingToThrowStar = Random.nextBoolean(),
        ),
    )
}
