package uk.co.rafearnold.themind

import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Tests {

  @Test
  fun `all cards played in correct order`() {
    val server = SimpleServer(gameConfig = GameConfig(roundCount = 1))
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
    val server = SimpleServer(gameConfig = GameConfig(roundCount = 1))
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
    val server = SimpleServer(gameConfig = GameConfig(roundCount = 3))
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
}

private fun List<Player>.nextPlayer(): Player =
  minByOrNull { (it.state as InGame).cards.minOfOrNull { card -> card.value } ?: Int.MAX_VALUE }!!

private fun Player.assertInGameWithOneCard() {
  this.assertInGameWithNCards(1)
}

private fun Player.assertInGameWithNoCards() {
  this.assertInGameWithNCards(0)
}

private fun Player.assertInGameWithNCards(n: Int) {
  assertIs<InGame>(state)
  val cards = (state as InGame).cards
  assertEquals(n, cards.size)
  for (i in 0 until n) {
    val card = cards[i]
    assertTrue(card.value >= 1)
    assertTrue(card.value <= 100)
  }
}

private fun List<Player>.assertNoDuplicateCards() {
  val allCards = flatMap { (it.state as InGame).cards.map { card -> card.value } }
  assertEquals(allCards, allCards.distinct())
}

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
    (nextPlayer.state as InGame).cards.apply { remove(minByOrNull { it.value }) }
  }

  private fun createInGamePlayer(vararg cardValues: Int): Player =
    Player(
      gameId = UUID.randomUUID().toString(),
      isHost = Random.nextBoolean(),
      state = InGame(cards = cardValues.map { Card(value = it) }.shuffled().toMutableList()),
    )
}
