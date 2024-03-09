package uk.co.rafearnold.mind

import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Tests {

  @Test
  fun `all cards played in correct order`() {
    val server = SimpleServer()
    val (host, lobbyId) = server.createLobby()
    assertEquals(InLobby, host.state)
    val player2 = server.joinLobby(lobbyId)
    assertEquals(InLobby, player2.state)
    val player3 = server.joinLobby(lobbyId)
    assertEquals(InLobby, player3.state)
    server.startGame(host)
    assertInGameWithOneCard(player = host)
    assertInGameWithOneCard(player = player2)
    assertInGameWithOneCard(player = player3)
    val players = mutableListOf(host, player2, player3)
    players.assertNoDuplicateCards()
    var nextPlayer = players.takeNextPlayer()!!
    server.playCard(nextPlayer)
    assertInGameWithNoCards(player = nextPlayer)
    players.forEach { assertInGameWithOneCard(player = it) }
    nextPlayer = players.takeNextPlayer()!!
    server.playCard(nextPlayer)
    assertInGameWithNoCards(player = nextPlayer)
    players.forEach { assertInGameWithOneCard(player = it) }
    server.playCard(players[0])
    assertEquals(GameWon, host.state)
    assertEquals(GameWon, player2.state)
    assertEquals(GameWon, player3.state)
  }

  @Test
  fun `card played in wrong order`() {
    val server = SimpleServer()
    val (host, lobbyId) = server.createLobby()
    val player2 = server.joinLobby(lobbyId)
    val player3 = server.joinLobby(lobbyId)
    server.startGame(host)
    val players = mutableListOf(host, player2, player3)
    val nextPlayer = players.takeNextPlayer()!!
    server.playCard(nextPlayer)
    players.forEach { assertInGameWithOneCard(player = it) }
    players.takeNextPlayer()!!
    server.playCard(players[0])
    assertEquals(GameLost, host.state)
    assertEquals(GameLost, player2.state)
    assertEquals(GameLost, player3.state)
  }
}

private fun MutableList<Player>.takeNextPlayer(): Player? =
  minByOrNull { (it.state as InGame).cards[0].value }.also { remove(it) }

private fun assertInGameWithOneCard(player: Player) {
  assertInGameWithNCards(player, 1)
}

private fun assertInGameWithNoCards(player: Player) {
  assertInGameWithNCards(player, 0)
}

private fun assertInGameWithNCards(
  player: Player,
  n: Int,
) {
  assertIs<InGame>(player.state)
  val cards = (player.state as InGame).cards
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
  fun `takes next player`() {
    val player1 = createInGamePlayer(1)
    val player2 = createInGamePlayer(2)
    val player3 = createInGamePlayer(3)
    val player4 = createInGamePlayer(4)
    val player5 = createInGamePlayer(5)
    val players = mutableListOf(player1, player4, player2, player5, player3)
    var nextPlayer = players.takeNextPlayer()
    assertEquals(player1, nextPlayer)
    assertEquals(listOf(player4, player2, player5, player3), players)
    nextPlayer = players.takeNextPlayer()
    assertEquals(player2, nextPlayer)
    assertEquals(listOf(player4, player5, player3), players)
    nextPlayer = players.takeNextPlayer()
    assertEquals(player3, nextPlayer)
    assertEquals(listOf(player4, player5), players)
    nextPlayer = players.takeNextPlayer()
    assertEquals(player4, nextPlayer)
    assertEquals(listOf(player5), players)
    nextPlayer = players.takeNextPlayer()
    assertEquals(player5, nextPlayer)
    assertEquals(listOf(), players)
  }

  private fun createInGamePlayer(vararg cardValues: Int): Player =
    Player(
      lobbyId = UUID.randomUUID().toString(),
      isHost = Random.nextBoolean(),
      state = InGame(cards = cardValues.map { Card(value = it) }.toMutableList()),
    )
}
