package uk.co.rafearnold.mind

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
    val playersSortedByCard = listOf(host, player2, player3).sortedBy { (it.state as InGame).cards[0].value }
    assertNoDuplicateCards(playersSortedByCard)
    server.playCard(playersSortedByCard[0])
    assertInGameWithNoCards(player = playersSortedByCard[0])
    assertInGameWithOneCard(player = playersSortedByCard[1])
    assertInGameWithOneCard(player = playersSortedByCard[2])
    server.playCard(playersSortedByCard[1])
    assertInGameWithNoCards(player = playersSortedByCard[0])
    assertInGameWithNoCards(player = playersSortedByCard[1])
    assertInGameWithOneCard(player = playersSortedByCard[2])
    server.playCard(playersSortedByCard[2])
    assertEquals(GameWon, host.state)
    assertEquals(GameWon, player2.state)
    assertEquals(GameWon, player3.state)
  }

  @Test
  fun `card played in wrong order`() {
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
    val playersSortedByCard = listOf(host, player2, player3).sortedBy { (it.state as InGame).cards[0].value }
    server.playCard(playersSortedByCard[0])
    assertInGameWithNoCards(player = playersSortedByCard[0])
    assertInGameWithOneCard(player = playersSortedByCard[1])
    assertInGameWithOneCard(player = playersSortedByCard[2])
    server.playCard(playersSortedByCard[2])
    assertEquals(GameLost, host.state)
    assertEquals(GameLost, player2.state)
    assertEquals(GameLost, player3.state)
  }

  private fun assertInGameWithOneCard(player: Player) {
    assertIs<InGame>(player.state)
    val cards = (player.state as InGame).cards
    assertEquals(1, cards.size)
    val card = cards[0]
    assertTrue(card.value >= 1)
    assertTrue(card.value <= 100)
  }

  private fun assertInGameWithNoCards(player: Player) {
    assertIs<InGame>(player.state)
    val cards = (player.state as InGame).cards
    assertEquals(0, cards.size)
  }

  private fun assertNoDuplicateCards(players: List<Player>) {
    val allCards = players.flatMap { (it.state as InGame).cards.map { card -> card.value } }
    assertEquals(allCards, allCards.distinct())
  }
}
