package uk.co.rafearnold.themind

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.Cookie
import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EndToEndTests {

  companion object {
    private lateinit var playwright: Playwright

    @BeforeAll
    @JvmStatic
    fun startup() {
      playwright = Playwright.create()
    }

    @AfterAll
    @JvmStatic
    fun tearAllDown() {
      playwright.close()
    }
  }

  private val browser: Browser = playwright.chromium().launch()
  private lateinit var server: Http4kServer

  @AfterEach
  fun tearEachDown() {
    browser.close()
    server.stop()
  }

  @Test
  fun `complete full game`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))
    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNCards(1) }
    repeat(3) { allPlayers.nextPlayer().playCard() }
    allPlayers.forEach { it.assertHasNCards(0) }
    allPlayers.forEach { it.toggleReady() }
    allPlayers.forEach { it.assertHasNCards(2) }
    repeat(6) { allPlayers.nextPlayer().playCard() }
    allPlayers.forEach { it.assertHasNCards(0) }
    allPlayers.forEach { it.toggleReady() }
    allPlayers.forEach { it.assertHasNCards(3) }
    repeat(9) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach { it.assertHasWon() }
  }

  @Test
  fun `lose a game`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))
    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNLives(1) }

    val incorrectNextPlayer = allPlayers.first { it != allPlayers.nextPlayer() }
    incorrectNextPlayer.playCard()

    allPlayers.forEach { it.assertHasLost() }
  }

  @Test
  fun `throw a star`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 1))
    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNThrowingStars(1) }

    // Complete first round.
    repeat(3) { allPlayers.nextPlayer().playCard() }

    // Start second round.
    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { it.assertHasNThrowingStars(1) }

    // Some vote to throw star.
    val initialVotingPlayers = allPlayers.take(2)
    initialVotingPlayers.forEach { it.toggleVoteToThrowStar() }

    // Votes are visible to all players.
    allPlayers.forEach { p ->
      p.assertOtherPlayersAreVoting(initialVotingPlayers.filter { it != p }.map { it.name })
      if (initialVotingPlayers.contains(p)) p.assertIsVoting() else p.assertIsNotVoting()
    }

    allPlayers.forEach { it.assertHasNThrowingStars(1) }

    // Play a card.
    allPlayers.nextPlayer().playCard()

    // Votes reset.
    allPlayers.forEach {
      it.assertIsNotVoting()
      it.assertOtherPlayersAreVoting(emptyList())
    }

    val expectedCards = allPlayers.associate { it.name to it.cardValues().sorted().drop(1) }

    allPlayers.forEach { it.assertHasNThrowingStars(1) }

    // All vote.
    allPlayers.forEach { it.toggleVoteToThrowStar() }

    allPlayers.forEach {
      it.assertIsNotVoting()
      it.assertOtherPlayersAreVoting(emptyList())
      it.assertHasNThrowingStars(0)
    }

    // Lowest card of each player removed.
    allPlayers.forEach { it.assertHasCards(expectedCards[it.name]!!) }
  }

  @Test
  fun `lose a life when incorrect card is played`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 3, startingStarsCount = 0))
    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNLives(3) }

    // Complete first round.
    repeat(3) { allPlayers.nextPlayer().playCard() }

    // Start second round.
    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { it.assertHasNLives(3) }

    allPlayers.first { it != allPlayers.nextPlayer() }.playCard()

    allPlayers.forEach { it.assertHasNLives(2) }
  }

  @Test
  fun `can refresh in lobby`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.createPlayers(browser, 3)
    val gameId: String = allPlayers[0].createGame()
    allPlayers.drop(1).forEach { it.joinGame(gameId) }

    allPlayers.forEach { it.page.reload() }

    allPlayers.forEach { assertThat(it.page.gameIdDisplay()).hasText(gameId) }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { it.assertHasNLives(1) }
  }

  @Test
  fun `can refresh in game`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNLives(1) }

    allPlayers.forEach { it.page.reload() }

    allPlayers.forEach { it.assertHasNLives(1) }

    val nextPlayer = allPlayers.nextPlayer()
    nextPlayer.playCard()

    allPlayers.first { it != allPlayers.nextPlayer() && it != nextPlayer }.playCard()

    allPlayers.forEach { it.assertHasLost() }
  }

  @Test
  fun `can refresh after game won`() {
    server = startServer(gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)
    repeat(3) { allPlayers.nextPlayer().playCard() }
    allPlayers.forEach { it.assertHasWon() }

    allPlayers.forEach { it.page.reload() }

    allPlayers.forEach { it.assertHasWon() }
  }

  @Test
  fun `can refresh after game lost`() {
    server = startServer(gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)
    allPlayers.first { it != allPlayers.nextPlayer() }.playCard()
    allPlayers.forEach { it.assertHasLost() }

    allPlayers.forEach { it.page.reload() }

    allPlayers.forEach { it.assertHasLost() }
  }

  @Test
  fun `vote button is disabled when no throwing stars available`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 1))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNThrowingStars(1) }
    allPlayers.forEach { it.assertVoteButtonEnabled() }

    allPlayers.forEach { it.toggleVoteToThrowStar() }

    allPlayers.forEach { it.assertHasNThrowingStars(0) }
    allPlayers.forEach { it.assertVoteButtonDisabled() }
  }

  @Test
  fun `all players in lobby are displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.createPlayers(browser, 3)

    val gameId: String = allPlayers[0].createGame()
    allPlayers[0].assertAllPlayersAre(listOf(allPlayers[0].name))

    allPlayers[1].joinGame(gameId)
    allPlayers[0].assertAllPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name))
    allPlayers[1].assertAllPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name))

    allPlayers[2].joinGame(gameId)
    allPlayers.forEach { it.assertAllPlayersAre(allPlayers.map { p -> p.name }) }
  }

  @Test
  fun `other players in game are displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { p ->
      p.assertOtherPlayersAre(allPlayers.filter { it != p }.map { it.name })
    }
  }

  @Test
  fun `connecting as unidentified player goes to home`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val cookieName = "themind_playerid"

    // Check that we have the correct cookie name.
    val controlContext = browser.newContext()
    val controlPlayer = controlContext.newPage()
    controlPlayer.navigateToHome(port = server.port())
    val gameId = controlPlayer.createGame(name = "control")
    assertEquals(1, controlContext.cookies().size)
    val controlCookie = controlContext.cookies()[0]
    assertEquals(cookieName, controlCookie.name)

    // Connect as an unidentified player.
    val playerContext = browser.newContext()
    val invalidCookieValue = "invalid player id"
    val playerCookie =
      Cookie(cookieName, invalidCookieValue)
        .setDomain(controlCookie.domain)
        .setPath(controlCookie.path)
    playerContext.addCookies(listOf(playerCookie))
    val playerPage = playerContext.newPage()
    playerPage.navigateToHome(port = server.port())

    assertThat(playerPage.createGameButton()).isVisible()
    playerPage.joinGame(gameId = gameId, name = "test")

    assertEquals(1, playerContext.cookies().size)
    assertEquals(cookieName, playerContext.cookies()[0].name)
    assertNotEquals(invalidCookieValue, playerContext.cookies()[0].value)
  }

  @Test
  fun `can leave lobby`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.createNewGame(browser)

    allPlayers.forEach {
      it.assertAllPlayersAre(allPlayers.map { p -> p.name })
      assertThat(it.page.leaveButton()).isVisible()
      assertThat(it.page.gameIdDisplay()).isVisible()
    }

    allPlayers[2].leaveGame(confirm = false)
    allPlayers.take(2).forEach { it.assertAllPlayersAre(allPlayers.take(2).map { p -> p.name }) }

    allPlayers[1].leaveGame(confirm = false)
    allPlayers.take(1).forEach { it.assertAllPlayersAre(allPlayers.take(1).map { p -> p.name }) }

    allPlayers[0].leaveGame(confirm = false)

    allPlayers.forEach { assertThat(it.page.gameIdDisplay()).not().isAttached() }
  }

  @Test
  fun `last player can leave lobby`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.createNewGame(browser)

    allPlayers[0].leaveGame(confirm = false)
    allPlayers[2].leaveGame(confirm = false)
    allPlayers[1].leaveGame(confirm = false)
  }

  @Test
  fun `can leave game which ends game for all`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach {
      assertThat(it.page.leaveButton()).isVisible()
      assertThat(it.page.currentLivesCountDisplay()).isVisible()
    }

    allPlayers[2].leaveGame(confirm = true)

    assertThat(allPlayers[2].page.createGameButton()).isVisible()
    allPlayers.forEach { assertThat(it.page.currentLivesCountDisplay()).not().isAttached() }

    allPlayers.take(2).forEach {
      it.assertPlayerHasLeft(playerName = allPlayers[2].name)
      assertThat(it.page.playerLeftText()).isVisible()
      assertThat(it.page.leaveButton()).isVisible()
      it.leaveGame(confirm = false)
      assertThat(it.page.createGameButton()).isVisible()
      assertThat(it.page.playerLeftText()).not().isAttached()
    }
  }

  @Test
  fun `can leave after game won`() {
    server = startServer(gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)

    repeat(3) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach {
      it.assertHasWon()
      assertThat(it.page.winnerText()).isVisible()
      assertThat(it.page.leaveButton()).isVisible()
      it.leaveGame(confirm = false)
      assertThat(it.page.winnerText()).not().isAttached()
    }
  }

  @Test
  fun `can leave after game lost`() {
    server = startServer(gameConfig(roundCount = 1, startingLivesCount = 1, startingStarsCount = 0))

    val allPlayers = server.startNewGame(browser)

    allPlayers.first { it != allPlayers.nextPlayer() }.playCard()

    allPlayers.forEach {
      it.assertHasLost()
      assertThat(it.page.loserText()).isVisible()
      assertThat(it.page.leaveButton()).isVisible()
      it.leaveGame(confirm = false)
      assertThat(it.page.loserText()).not().isAttached()
    }
  }

  @Test
  fun `other player card counts are displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 2, startingStarsCount = 1))

    val allPlayers = server.startNewGame(browser)

    var expectedCounts = allPlayers.associateWith { 1 }.toMutableMap()
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }

    // Play incorrect card.
    val sortedPlayers = allPlayers.sortedByMinCardValue()
    val incorrectPlayer = sortedPlayers[1]
    incorrectPlayer.playCard()
    expectedCounts[sortedPlayers[0]] = expectedCounts[sortedPlayers[0]]!! - 1
    expectedCounts[incorrectPlayer] = expectedCounts[incorrectPlayer]!! - 1
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }

    // Play the last card of the round.
    allPlayers.nextPlayer().playCard()
    expectedCounts = allPlayers.associateWith { 0 }.toMutableMap()
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }

    // Start the next round.
    allPlayers.forEach { it.toggleReady() }
    expectedCounts = allPlayers.associateWith { 2 }.toMutableMap()
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }

    // Play the correct card.
    val nextPlayer = allPlayers.nextPlayer()
    nextPlayer.playCard()
    expectedCounts[nextPlayer] = expectedCounts[nextPlayer]!! - 1
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }

    // Throw a star.
    allPlayers.forEach { it.toggleVoteToThrowStar() }
    expectedCounts = expectedCounts.mapValues { it.value - 1 }.toMutableMap()
    allPlayers.forEach { p ->
      p.assertOtherPlayerCardCountsAre(expectedCounts.filterKeys { p != it })
    }
  }

  @Test
  fun `last played card is displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 2, startingStarsCount = 1))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(null) }

    // Play incorrect card.
    val sortedPlayers = allPlayers.sortedByMinCardValue()
    val incorrectPlayer = sortedPlayers[1]
    val incorrectPlayerMinCardValue = incorrectPlayer.minCardValue()
    incorrectPlayer.playCard()
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(incorrectPlayerMinCardValue) }

    // Play the last card of the round.
    var nextPlayer = allPlayers.nextPlayer()
    var nextPlayerMinCardValue = nextPlayer.minCardValue()
    nextPlayer.playCard()
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(nextPlayerMinCardValue) }

    // Start the next round.
    allPlayers.forEach { it.toggleReady() }
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(null) }

    // Play the correct card.
    nextPlayer = allPlayers.nextPlayer()
    nextPlayerMinCardValue = nextPlayer.minCardValue()
    nextPlayer.playCard()
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(nextPlayerMinCardValue) }

    // Throw a star.
    allPlayers.forEach { it.toggleVoteToThrowStar() }
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(nextPlayerMinCardValue) }

    // Play the correct card.
    nextPlayer = allPlayers.nextPlayer()
    nextPlayerMinCardValue = nextPlayer.minCardValue()
    nextPlayer.playCard()
    allPlayers.forEach { p -> p.assertLastPlayedCardValueIs(nextPlayerMinCardValue) }
  }

  @Test
  fun `round number is displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 1))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertRoundIs(1) }

    repeat(3) {
      allPlayers.nextPlayer().playCard()
      allPlayers.forEach { it.assertRoundIs(1) }
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { it.assertRoundIs(2) }
    repeat(6) {
      allPlayers.nextPlayer().playCard()
      allPlayers.forEach { it.assertRoundIs(2) }
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { it.assertRoundIs(3) }
  }

  @Test
  fun `can revoke vote to throw star`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 1))

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach {
      it.assertIsNotVoting()
      it.assertOtherPlayersAreVoting(listOf())
    }

    allPlayers[0].toggleVoteToThrowStar()
    allPlayers[0].assertIsVoting()
    allPlayers[0].assertOtherPlayersAreVoting(listOf())
    allPlayers.drop(1).forEach { it.assertOtherPlayersAreVoting(listOf(allPlayers[0].name)) }

    allPlayers[0].toggleVoteToThrowStar()
    allPlayers[0].assertIsNotVoting()
    allPlayers.forEach { it.assertOtherPlayersAreVoting(listOf()) }

    allPlayers.drop(1).forEach {
      it.toggleVoteToThrowStar()
      it.assertIsVoting()
    }
    allPlayers[0].assertOtherPlayersAreVoting(listOf(allPlayers[1].name, allPlayers[2].name))
    allPlayers[1].assertOtherPlayersAreVoting(listOf(allPlayers[2].name))
    allPlayers[2].assertOtherPlayersAreVoting(listOf(allPlayers[1].name))

    allPlayers[1].toggleVoteToThrowStar()
    allPlayers[1].assertIsNotVoting()
    allPlayers.dropLast(1).forEach { it.assertOtherPlayersAreVoting(listOf(allPlayers[2].name)) }
    allPlayers[2].assertOtherPlayersAreVoting(listOf())

    allPlayers[0].toggleVoteToThrowStar()
    allPlayers[0].assertIsVoting()
    allPlayers[0].assertOtherPlayersAreVoting(listOf(allPlayers[2].name))
    allPlayers[1].assertOtherPlayersAreVoting(listOf(allPlayers[0].name, allPlayers[2].name))
    allPlayers[2].assertOtherPlayersAreVoting(listOf(allPlayers[0].name))

    allPlayers.forEach {
      it.assertRoundIs(1)
      it.assertHasNCards(1)
    }

    allPlayers[1].toggleVoteToThrowStar()
    allPlayers.forEach {
      it.assertIsNotVoting()
      it.assertOtherPlayersAreVoting(listOf())
      it.assertRoundIs(1)
      it.assertHasNCards(0)
    }
  }

  @Test
  fun `all cards played in the round are displayed`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 2, startingStarsCount = 2))

    val allPlayers = server.startNewGame(browser)

    val expectedPlayedCards: MutableList<Int> = mutableListOf()
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Complete first round.
    repeat(3) {
      allPlayers.nextPlayer().run {
        expectedPlayedCards.add(minCardValue())
        playCard()
      }
      allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }
    }

    // Start second round.
    allPlayers.forEach { it.toggleReady() }
    expectedPlayedCards.clear()
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Throw a star.
    allPlayers.sortedByMinCardValue().forEach {
      expectedPlayedCards.add(it.minCardValue())
      it.toggleVoteToThrowStar()
    }
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Play incorrect card.
    expectedPlayedCards.add(allPlayers.sortedByMinCardValue()[0].minCardValue())
    expectedPlayedCards.add(allPlayers.sortedByMinCardValue()[1].minCardValue())
    allPlayers.sortedByMinCardValue()[1].playCard()
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Complete second round.
    allPlayers.nextPlayer().run {
      expectedPlayedCards.add(minCardValue())
      playCard()
    }
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Start third round.
    allPlayers.forEach { it.toggleReady() }
    expectedPlayedCards.clear()
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }

    // Play a couple cards in the third round.
    repeat(2) {
      allPlayers.nextPlayer().run {
        expectedPlayedCards.add(minCardValue())
        playCard()
      }
      allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }
    }

    // Throw a star.
    allPlayers.sortedByMinCardValue().forEach {
      // Voted out cards should be behind the last played card.
      expectedPlayedCards.add(expectedPlayedCards.size - 2, it.minCardValue())
      it.toggleVoteToThrowStar()
    }
    allPlayers.forEach { it.assertPlayedCardsAre(expectedPlayedCards) }
  }

  @Test
  fun `can not start a game with less than 2 players`() {
    server = startServer(gameConfig(roundCount = 3, startingLivesCount = 2, startingStarsCount = 2))

    val players = server.createPlayers(browser, 2)
    val player1 = players[0]
    val gameId = player1.createGame()

    player1.assertReadyPlayersAre(emptyList())

    // Readying when only one player does not start the game.
    player1.toggleReady()
    player1.assertReadyPlayersAre(listOf(player1.name))
    assertThat(player1.page.currentRoundDisplay()).not().isAttached()

    val player2 = players[1]
    player2.joinGame(gameId = gameId)

    player2.toggleReady()
    players.forEach {
      assertThat(it.page.toggleReadyButton()).not().isAttached()
      it.assertRoundIs(1)
    }
  }

  @Test
  fun `level reward is displayed`() {
    server = startServer()

    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach {
      it.assertHasNLives(3)
      it.assertHasNThrowingStars(1)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    repeat(3) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach {
      it.assertHasNLives(3)
      it.assertHasNThrowingStars(1)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach {
      it.assertRoundIs(2)
      it.assertHasNLives(3)
      it.assertHasNThrowingStars(1)
      it.assertLevelRewardIs(LevelReward.STAR)
    }

    repeat(6) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach {
      it.assertRoundIs(2)
      it.assertHasNLives(3)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach {
      it.assertRoundIs(3)
      it.assertHasNLives(3)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.LIFE)
    }

    repeat(9) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach {
      it.assertRoundIs(3)
      it.assertHasNLives(4)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach {
      it.assertRoundIs(4)
      it.assertHasNLives(4)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    repeat(12) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach {
      it.assertRoundIs(4)
      it.assertHasNLives(4)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.NONE)
    }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach {
      it.assertRoundIs(5)
      it.assertHasNLives(4)
      it.assertHasNThrowingStars(2)
      it.assertLevelRewardIs(LevelReward.STAR)
    }
  }

  @Test
  fun `all players must be ready before starting`() {
    server = startServer()

    val allPlayers = server.createNewGame(browser)

    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }

    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name)) }

    allPlayers[2].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[2].name)) }

    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[2].name)) }

    allPlayers[1].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[1].name, allPlayers[2].name)) }

    allPlayers[0].toggleReady()
    allPlayers.forEach {
      assertThat(it.page.toggleReadyButton()).not().isAttached()
      it.assertRoundIs(1)
    }
  }

  @Test
  fun `when the only unready player leaves the lobby then the game starts`() {
    server = startServer()

    val allPlayers = server.createNewGame(browser)

    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }

    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name)) }

    allPlayers[2].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[2].name)) }

    allPlayers[1].leaveGame(confirm = false)
    assertThat(allPlayers[0].page.toggleReadyButton()).not().isAttached()
    allPlayers[0].assertRoundIs(1)
    assertThat(allPlayers[2].page.toggleReadyButton()).not().isAttached()
    allPlayers[2].assertRoundIs(1)
  }

  @Test
  fun `websocket connection is re-established if closed`() {
    server = startServer()

    val allPlayers = server.createNewGame(browser)

    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }

    // Code to add an event listener to force the websocket connection to close upon receiving its
    // next message. A bit of a hack to simulate sudden websocket closure.
    val wsCloser =
      "document.addEventListener('htmx:wsAfterMessage', (e) => setTimeout(() => " +
        "e.detail.elt['htmx-internal-data'].webSocket.close()), { once: true })"

    // Trigger websocket closure for first page.
    allPlayers[0].page.evaluate(wsCloser)
    allPlayers[0].page.waitForWebSocket { allPlayers[0].toggleReady() }

    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name)) }

    // Trigger websocket closure for second page.
    allPlayers[1].page.evaluate(wsCloser)
    allPlayers[1].page.waitForWebSocket { allPlayers[1].toggleReady() }

    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name)) }

    // Trigger websocket closure for third page.
    allPlayers[2].page.evaluate(wsCloser)
    allPlayers[2].page.waitForWebSocket { allPlayers[0].toggleReady() }

    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[1].name)) }
    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name)) }
    allPlayers[2].toggleReady()
    allPlayers.forEach { it.assertRoundIs(1) }
  }

  @Test
  fun `after round completion all players have to ready up before next round starts`() {
    server = startServer()

    val allPlayers = server.startNewGame(browser)

    repeat(3) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }
    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name)) }
    allPlayers[1].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name)) }
    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[1].name)) }
    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[0].name, allPlayers[1].name)) }
    allPlayers[2].toggleReady()
    allPlayers.forEach { it.assertRoundIs(2) }

    repeat(6) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }
    allPlayers[2].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[2].name)) }
    allPlayers[1].toggleReady()
    allPlayers.forEach { it.assertReadyPlayersAre(listOf(allPlayers[1].name, allPlayers[2].name)) }
    allPlayers[0].toggleReady()
    allPlayers.forEach { it.assertRoundIs(3) }
  }

  @Test
  fun `player list is not shown mid-round`() {
    server = startServer()

    val allPlayers = server.startNewGame(browser)

    allPlayers.nextPlayer().playCard()

    allPlayers.forEach { assertThat(it.page.allPlayersList()).not().isVisible() }

    allPlayers.nextPlayer().playCard()

    allPlayers.forEach { assertThat(it.page.allPlayersList()).not().isVisible() }

    allPlayers.nextPlayer().playCard()

    allPlayers.forEach { assertThat(it.page.allPlayersList()).isVisible() }
    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }

    allPlayers.forEach { it.toggleReady() }

    allPlayers.forEach { assertThat(it.page.allPlayersList()).not().isVisible() }

    repeat(6) { allPlayers.nextPlayer().playCard() }

    allPlayers.forEach { assertThat(it.page.allPlayersList()).isVisible() }
    allPlayers.forEach { it.assertReadyPlayersAre(emptyList()) }
  }
}

private fun startServer(gameConfig: GameConfig) = startServer(InMemoryServer(gameConfig))

private fun gameConfig(
  roundCount: Int,
  startingLivesCount: Int,
  startingStarsCount: Int,
): GameConfig =
  GameConfig(roundCount = { roundCount }, startingStarsCount = startingStarsCount) {
    startingLivesCount
  }

private fun Http4kServer.startNewGame(browser: Browser): List<PlayerContext> {
  val players = createNewGame(browser)
  players.forEach { it.toggleReady() }
  players.forEach {
    assertThat(it.page.toggleReadyButton()).not().isAttached()
    it.assertRoundIs(1)
  }
  return players
}

private fun Http4kServer.createNewGame(browser: Browser): List<PlayerContext> {
  val players: List<PlayerContext> = createPlayers(browser, 3)
  val gameId: String = players[0].createGame()
  players.drop(1).forEach { it.joinGame(gameId) }
  return players
}

private fun Http4kServer.createPlayers(
  browser: Browser,
  n: Int,
): List<PlayerContext> =
  browser.createPlayerContexts(n)
    .onEach {
      // Ensure everything is self-hosted by killing all requests outside the local server.
      it.page.context()
        .route(Pattern.compile("^(?!http://localhost:${port()}).*")) { route -> route.abort() }
      it.navigateToHome(port = port())
    }

private fun Browser.createPlayerContexts(n: Int): List<PlayerContext> =
  playerNames.shuffled().take(n).map { PlayerContext(name = it, page = newContext().newPage()) }

private fun PlayerContext.assertPlayerHasLeft(playerName: String) {
  page.assertPlayerHasLeft(playerName = playerName)
}

private fun Page.assertPlayerHasLeft(playerName: String) {
  assertThat(playerLeftText()).hasText("$playerName left")
}

private fun PlayerContext.assertLevelRewardIsEmpty() {
  page.assertLevelRewardIsEmpty()
}

private fun Page.assertLevelRewardIsEmpty() {
  assertThat(starLevelReward()).not().isVisible()
  assertThat(starLevelReward()).not().isAttached()
  assertThat(lifeLevelReward()).not().isVisible()
  assertThat(lifeLevelReward()).not().isAttached()
  assertThat(levelReward()).isEmpty()
}

private fun PlayerContext.assertLevelRewardIs(expected: LevelReward) {
  when (expected) {
    LevelReward.NONE -> assertLevelRewardIsEmpty()
    LevelReward.LIFE -> assertLevelRewardIsLife()
    LevelReward.STAR -> assertLevelRewardIsStar()
  }
}

private fun PlayerContext.assertLevelRewardIsStar() {
  page.assertLevelRewardIsStar()
}

private fun Page.assertLevelRewardIsStar() {
  assertThat(starLevelReward()).isVisible()
  assertThat(starLevelReward()).isAttached()
}

private fun PlayerContext.assertLevelRewardIsLife() {
  page.assertLevelRewardIsLife()
}

private fun Page.assertLevelRewardIsLife() {
  assertThat(lifeLevelReward()).isVisible()
  assertThat(lifeLevelReward()).isAttached()
}

private fun PlayerContext.assertRoundIs(n: Int) {
  page.assertRoundIs(n = n)
}

private fun Page.assertRoundIs(n: Int) {
  val roundNumber = currentRoundDisplay()
  assertThat(roundNumber).isVisible()
  assertThat(roundNumber).hasText(n.toString())
}

private fun PlayerContext.assertLastPlayedCardValueIs(value: Int?) {
  page.assertLastPlayedCardValueIs(value = value)
}

private fun Page.assertLastPlayedCardValueIs(value: Int?) {
  val cardValue = playedCards().last()
  if (value != null) {
    assertThat(cardValue).hasCount(1)
    assertThat(cardValue).hasText(value.toString())
  } else {
    assertThat(cardValue).hasCount(0)
    assertThat(cardValue).not().isAttached()
  }
}

private fun PlayerContext.assertPlayedCardsAre(values: List<Int>) {
  page.assertPlayedCardsAre(values = values)
}

private fun Page.assertPlayedCardsAre(values: List<Int>) {
  val cardValues = playedCards()
  assertThat(cardValues).hasCount(values.size)
  assertThat(cardValues).hasText(values.map { it.toString() }.toTypedArray())
}

private fun PlayerContext.assertReadyPlayersAre(names: List<String>) {
  page.assertReadyPlayersAre(names = names)
}

private fun Page.assertReadyPlayersAre(names: List<String>) {
  val readyPlayers =
    allPlayersList()
      .locator("[data-testid='player']:has([data-testid='is-ready-display'])")
      .getByTestId("player-name")
  assertThat(readyPlayers).hasCount(names.size)
  assertThat(readyPlayers).hasText(names.toTypedArray())
}

private fun PlayerContext.assertAllPlayersAre(names: List<String>) {
  page.assertAllPlayersAre(names = names)
}

private fun Page.assertAllPlayersAre(names: List<String>) {
  val allPlayers = allPlayersList().getByTestId("player-name")
  assertThat(allPlayers).hasCount(names.size)
  assertThat(allPlayers).hasText(names.toTypedArray())
}

private fun PlayerContext.assertOtherPlayersAre(names: List<String>) {
  page.assertOtherPlayersAre(names = names)
}

private fun Page.assertOtherPlayersAre(names: List<String>) {
  val allPlayers = getByTestId("other-players").getByTestId("player-name")
  assertThat(allPlayers).hasCount(names.size)
  assertThat(allPlayers).hasText(names.toTypedArray())
}

private fun PlayerContext.assertOtherPlayerCardCountsAre(counts: Map<PlayerContext, Int>) {
  page.assertOtherPlayerCardCountsAre(counts = counts.mapKeys { it.key.name })
}

private fun Page.assertOtherPlayerCardCountsAre(counts: Map<String, Int>) {
  for ((playerName, count) in counts) {
    val cards =
      getByTestId("other-players")
        .locator("[data-testid='other-player']:has([data-testplayername='$playerName'])")
        .getByTestId("card")
    assertThat(cards).hasCount(count)
  }
}

private fun PlayerContext.assertHasNThrowingStars(n: Int) {
  page.assertHasNThrowingStars(n = n)
}

private fun Page.assertHasNThrowingStars(n: Int) {
  val throwingStarsDisplay = getByTestId("current-throwing-stars-count")
  assertThat(throwingStarsDisplay).isVisible()
  assertThat(throwingStarsDisplay).hasText(n.toString())
}

private fun PlayerContext.assertVoteButtonEnabled() {
  page.assertVoteButtonEnabled()
}

private fun Page.assertVoteButtonEnabled() {
  val voteButton = voteToThrowStarButton()
  assertThat(voteButton).isVisible()
  assertThat(voteButton).isEnabled()
}

private fun PlayerContext.assertVoteButtonDisabled() {
  page.assertVoteButtonDisabled()
}

private fun Page.assertVoteButtonDisabled() {
  val voteButton = voteToThrowStarButton()
  assertThat(voteButton).isVisible()
  assertThat(voteButton).isDisabled()
}

private fun PlayerContext.assertHasNLives(n: Int) {
  page.assertHasNLives(n = n)
}

private fun Page.assertHasNLives(n: Int) {
  val livesDisplay = currentLivesCountDisplay()
  assertThat(livesDisplay).isVisible()
  assertThat(livesDisplay).hasText(n.toString())
}

private fun PlayerContext.assertIsVoting() {
  page.assertIsVoting()
}

private fun Page.assertIsVoting() {
  assertThat(isVotingToThrowStarCheckbox()).isChecked()
  assertThat(voteToThrowStarButton()).hasCSS("color", "rgb(245, 158, 11)")
}

private fun PlayerContext.assertIsNotVoting() {
  page.assertIsNotVoting()
}

private fun Page.assertIsNotVoting() {
  assertThat(isVotingToThrowStarCheckbox()).not().isChecked()
  assertThat(voteToThrowStarButton()).hasCSS("color", "rgb(228, 228, 231)")
}

private fun PlayerContext.assertOtherPlayersAreVoting(names: List<String>) {
  page.assertOtherPlayersAreVoting(names = names)
}

private fun Page.assertOtherPlayersAreVoting(names: List<String>) {
  val playersVoting =
    getByTestId("other-players")
      .locator("[data-testid='other-player']:has([data-testid='throwing-star-vote-indicator'])")
      .getByTestId("player-name")
  assertThat(playersVoting).hasCount(names.size)
  assertThat(playersVoting).hasText(names.toTypedArray())
}

private fun PlayerContext.toggleVoteToThrowStar() {
  page.toggleVoteToThrowStar()
}

private fun Page.toggleVoteToThrowStar() {
  voteToThrowStarButton().click()
}

private fun Page.voteToThrowStarButton(): Locator = getByTestId("vote-to-throw-star-button")

private fun Page.isVotingToThrowStarCheckbox(): Locator =
  getByTestId("is-voting-to-throw-star-checkbox")

private fun PlayerContext.assertHasLost() {
  page.assertHasLost()
}

private fun Page.assertHasLost() {
  assertThat(loserText()).isVisible()
}

private fun PlayerContext.assertHasWon() {
  page.assertHasWon()
}

private fun Page.assertHasWon() {
  assertThat(winnerText()).isVisible()
}

private fun PlayerContext.playCard() {
  page.playCard()
}

private fun Page.playCard() {
  val cardList = cardList()
  val cardValues = cardList.allTextContents().sortedByDescending { it.trim().toInt() }
  assertThat(cardList).hasText(cardValues.toTypedArray())
  val nextCard = cardList.nth(0)
  assertThat(nextCard).hasText(cardValues[0])
  getByTestId("play-card-button").click()
  assertThat(cardList).hasCount(cardValues.size - 1)
  assertThat(cardList).hasText(cardValues.dropLast(1).toTypedArray())
}

private fun List<PlayerContext>.sortedByMinCardValue(): List<PlayerContext> =
  sortedBy { it.minCardValue() }

private fun List<PlayerContext>.nextPlayer(): PlayerContext = minByOrNull { it.minCardValue() }!!

private fun PlayerContext.minCardValue(): Int = page.minCardValue()

private fun Page.minCardValue(): Int = cardValues().minOrNull() ?: Int.MAX_VALUE

private fun PlayerContext.cardValues(): List<Int> = page.cardValues()

private fun Page.cardValues(): List<Int> = cardList().allTextContents().map { it.trim().toInt() }

private val cardValuePattern: Pattern = Pattern.compile("^\\s*([1-9]\\d?|100)\\s*$")

private fun PlayerContext.assertHasCards(values: List<Int>) {
  page.assertHasCards(values)
}

private fun Page.assertHasCards(values: List<Int>) {
  assertThat(cardList()).hasText(values.map { it.toString() }.toTypedArray())
}

private fun PlayerContext.assertHasNCards(n: Int) {
  page.assertHasNCards(n = n)
}

private fun Page.assertHasNCards(n: Int) {
  val cardList = cardList()
  assertThat(cardList).hasCount(n)
  assertThat(cardList).hasText((1..n).map { cardValuePattern }.toTypedArray())
}

private fun Page.allPlayersList(): Locator = getByTestId("all-players")

private fun Page.starLevelReward(): Locator = levelReward().getByTestId("star")

private fun Page.lifeLevelReward(): Locator = levelReward().getByTestId("life")

private fun Page.levelReward(): Locator = getByTestId("level-reward")

private fun Page.playedCards(): Locator = getByTestId("played-cards").getByTestId("card-value")

private fun Page.cardList(): Locator = getByTestId("card-list").getByTestId("card-value")

private fun PlayerContext.toggleReady() {
  page.toggleReady()
}

private fun Page.toggleReady() {
  toggleReadyButton().click()
}

private fun PlayerContext.createGame(): String = page.createGame(name = name)

private fun Page.createGame(name: String): String {
  fillPlayerNameInput(name = name)
  val createGameButton = createGameButton()
  createGameButton.click()
  assertThat(createGameButton).not().isAttached()
  assertThat(joinGameButton()).not().isAttached()
  val gameIdDisplay = gameIdDisplay()
  assertThat(gameIdDisplay).isVisible()
  return gameIdDisplay.textContent()
}

private fun PlayerContext.joinGame(gameId: String) {
  page.joinGame(gameId = gameId, name = name)
}

private fun Page.joinGame(
  gameId: String,
  name: String,
) {
  fillPlayerNameInput(name = name)
  val joinGameGameIdInput = getByTestId("join-game-game-id")
  joinGameGameIdInput.fill(gameId)
  val joinGameButton = joinGameButton()
  joinGameButton.click()
  assertThat(joinGameGameIdInput).not().isAttached()
  assertThat(joinGameButton).not().isAttached()
  assertThat(createGameButton()).not().isAttached()
  val gameIdDisplay = gameIdDisplay()
  assertThat(gameIdDisplay).isVisible()
  assertThat(gameIdDisplay).hasText(gameId)
}

private fun PlayerContext.leaveGame(confirm: Boolean) {
  page.leaveGame(confirm = confirm)
}

private fun Page.leaveGame(confirm: Boolean) {
  val latch = CountDownLatch(1)
  if (confirm) {
    onceDialog { dialog ->
      dialog.accept()
      latch.countDown()
    }
  }
  leaveButton().click()
  if (confirm) assertTrue(latch.await(5, TimeUnit.SECONDS))
  assertThat(createGameButton()).isVisible()
}

private fun Page.gameIdDisplay(): Locator = getByTestId("game-id")

private fun Page.currentRoundDisplay(): Locator? = getByTestId("current-round-num")

private fun Page.currentLivesCountDisplay(): Locator? = getByTestId("current-lives-count")

private fun Page.createGameButton(): Locator = getByTestId("create-game-button")

private fun Page.joinGameButton(): Locator = getByTestId("join-game-button")

private fun Page.toggleReadyButton(): Locator = getByTestId("toggle-ready-button")

private fun Page.winnerText(): Locator? = getByTestId("winner-text")

private fun Page.loserText(): Locator? = getByTestId("loser-text")

private fun Page.leaveButton(): Locator = getByTestId("leave-button")

private fun Page.playerLeftText(): Locator? = getByTestId("player-left-text")

private fun Page.fillPlayerNameInput(name: String) {
  getByTestId("player-name-input").fill(name)
}

private fun PlayerContext.navigateToHome(port: Int) {
  page.navigateToHome(port)
}

private fun Page.navigateToHome(port: Int) {
  navigate("http://localhost:$port")
  assertThat(this).hasTitle("The Mind")
}

private data class PlayerContext(val name: String, val page: Page)

private val playerNames =
  listOf("ali", "ben", "bob", "dan", "jil", "joe", "lew", "liv", "ned", "sue", "ted", "tom")
