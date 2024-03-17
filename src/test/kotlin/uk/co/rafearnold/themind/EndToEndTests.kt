package uk.co.rafearnold.themind

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
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
    val hostContext: BrowserContext = browser.newContext()
    val player2Context: BrowserContext = browser.newContext()
    val player3Context: BrowserContext = browser.newContext()

    val hostPage: Page = hostContext.newPage()
    hostPage.navigateToHome(port = server.port())
    val gameId: String = hostPage.createGame()

    val player2Page = player2Context.newPage()
    player2Page.navigateToHome(port = server.port())
    player2Page.joinGame(gameId)

    val player3Page = player3Context.newPage()
    player3Page.navigateToHome(port = server.port())
    player3Page.joinGame(gameId)

    hostPage.startGame()

    val allPlayers = listOf(hostPage, player2Page, player3Page)

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
}

private fun Page.assertHasWon() {
  assertThat(getByTestId("winner-text")).isVisible()
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

private fun List<Page>.nextPlayer(): Page = minByOrNull { it.minCardValue() }!!

private fun Page.minCardValue(): Int =
  cardList().allTextContents().minOfOrNull { it.trim().toInt() } ?: Int.MAX_VALUE

private val cardValuePattern: Pattern = Pattern.compile("^\\s*([1-9]\\d?|100)\\s*$")

private fun Page.assertHasNCards(n: Int) {
  val cardList = cardList()
  assertThat(cardList).hasCount(n)
  assertThat(cardList).hasText((1..n).map { cardValuePattern }.toTypedArray())
}

private fun Page.cardList(): Locator = getByTestId("card-list").getByTestId("card-item")

private fun Page.startGame() {
  val startGameButton = getByTestId("start-game-button")
  startGameButton.click()
  assertThat(startGameButton).not().isAttached()
}

private fun Page.createGame(): String {
  val createGameButton = createGameButton()
  createGameButton.click()
  assertThat(createGameButton).not().isAttached()
  assertThat(joinGameButton()).not().isAttached()
  val gameIdDisplay = gameIdDisplay()
  assertThat(gameIdDisplay).isVisible()
  return gameIdDisplay.textContent()
}

private fun Page.joinGame(gameId: String) {
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

private fun Page.navigateToHome(port: Int) {
  navigate("http://localhost:$port")
  assertThat(this).hasTitle("The Mind")
}
