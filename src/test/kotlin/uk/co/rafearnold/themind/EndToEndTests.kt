package uk.co.rafearnold.themind

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import java.util.regex.Pattern
import kotlin.test.Test

class EndToEndTests {

  companion object {
    private val playwright: Playwright = Playwright.create()

    @AfterAll
    @JvmStatic
    fun tearAllDown() {
      playwright.close()
    }
  }

  private val browser: Browser = playwright.chromium().launch()
  private val server: Http4kServer =
    startServer(GameConfig(roundCount = 3, startingLivesCount = 1, startingStarsCount = 0))

  @AfterEach
  fun tearEachDown() {
    browser.close()
    server.stop()
  }

  @Test
  fun `complete full game`() {
    val allPlayers = server.startNewGame(browser)

    allPlayers.forEach { it.assertHasNCards(1) }
    repeat(2) { allPlayers.nextPlayer().playCard(toCompleteRound = false) }
    allPlayers.nextPlayer().playCard(toCompleteRound = true)
    allPlayers.forEach { it.assertHasNCards(2) }
    repeat(5) { allPlayers.nextPlayer().playCard(toCompleteRound = false) }
    allPlayers.nextPlayer().playCard(toCompleteRound = true)
    allPlayers.forEach { it.assertHasNCards(3) }
    repeat(8) { allPlayers.nextPlayer().playCard(toCompleteRound = false) }
    allPlayers.nextPlayer().playCard(toCompleteRound = true)

    allPlayers.forEach { it.assertHasWon() }
  }

  @Test
  fun `lose a game`() {
    val allPlayers = server.startNewGame(browser)

    val incorrectNextPlayer = allPlayers.first { it != allPlayers.nextPlayer() }
    incorrectNextPlayer.playCard(toCompleteRound = true)

    allPlayers.forEach { it.assertHasLost() }
  }

  @Test
  fun `throw a star`() {
    val allPlayers = server.startNewGame(browser)

    // Complete first round.
    repeat(2) { allPlayers.nextPlayer().playCard(toCompleteRound = false) }
    allPlayers.nextPlayer().playCard(toCompleteRound = true)

    // Some vote to throw star.
    val initialVotingPlayers = allPlayers.take(2)
    initialVotingPlayers.forEach { it.voteToThrowStar() }

    // Votes are visible to all players.
    allPlayers.forEach { p -> p.assertPlayersAreVoting(initialVotingPlayers.map { it.name }) }

    // Play a card.
    allPlayers.nextPlayer().playCard(toCompleteRound = false)

    // Votes reset.
    allPlayers.forEach { it.assertPlayersAreVoting(emptyList()) }

    val expectedCards = allPlayers.associate { it.name to it.cardValues().sorted().drop(1) }

    // All vote.
    allPlayers.forEach { it.voteToThrowStar() }

    // Lowest card of each player removed.
    allPlayers.forEach { it.assertHasCards(expectedCards[it.name]!!) }
  }
}

private fun Http4kServer.startNewGame(browser: Browser): List<PlayerContext> {
  val players: List<PlayerContext> =
    playerNames.shuffled().take(3)
      .map { PlayerContext(name = it, page = browser.newContext().newPage()) }
  players.forEach { it.navigateToHome(port = port()) }
  val gameId: String = players[0].createGame()
  players.drop(1).forEach { it.joinGame(gameId) }
  players[0].startGame()
  return players
}

private fun PlayerContext.assertPlayersAreVoting(names: List<String>) {
  page.assertPlayersAreVoting(names = names)
}

private fun Page.assertPlayersAreVoting(names: List<String>) {
  val playersVoting = getByTestId("players-voting-to-throw-star").getByTestId("player-name")
  assertThat(playersVoting).hasCount(names.size)
  assertThat(playersVoting).hasText(names.toTypedArray())
}

private fun PlayerContext.voteToThrowStar() {
  page.voteToThrowStar()
}

private fun Page.voteToThrowStar() {
  getByTestId("vote-to-throw-star-button").click()
}

private fun PlayerContext.assertHasLost() {
  page.assertHasLost()
}

private fun Page.assertHasLost() {
  assertThat(getByTestId("loser-text")).isVisible()
}

private fun PlayerContext.assertHasWon() {
  page.assertHasWon()
}

private fun Page.assertHasWon() {
  assertThat(getByTestId("winner-text")).isVisible()
}

private fun PlayerContext.playCard(toCompleteRound: Boolean) {
  page.playCard(toCompleteRound = toCompleteRound)
}

private fun Page.playCard(toCompleteRound: Boolean) {
  val cardList = cardList()
  val cardValues = cardList.allTextContents().sortedBy { it.trim().toInt() }
  assertThat(cardList).hasText(cardValues.toTypedArray())
  val nextCard = cardList.nth(0)
  assertThat(nextCard).hasText(cardValues[0])
  nextCard.getByTestId("play-card-button").click()
  if (!toCompleteRound) {
    assertThat(cardList).hasCount(cardValues.size - 1)
    assertThat(cardList).hasText(cardValues.drop(1).toTypedArray())
  }
}

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

private fun Page.cardList(): Locator = getByTestId("card-list").getByTestId("card-item")

private fun PlayerContext.startGame() {
  page.startGame()
}

private fun Page.startGame() {
  val startGameButton = getByTestId("start-game-button")
  startGameButton.click()
  assertThat(startGameButton).not().isAttached()
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

private fun Page.gameIdDisplay(): Locator = getByTestId("game-id")

private fun Page.createGameButton(): Locator = getByTestId("create-game-button")

private fun Page.joinGameButton(): Locator = getByTestId("join-game-button")

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
