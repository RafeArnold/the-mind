package uk.co.rafearnold.themind

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.FileLoader
import org.http4k.template.TemplateRenderer
import org.http4k.template.ViewModel
import java.io.StringWriter

object HomeViewModel : ViewModel {
  override fun template(): String = "home"
}

data class LobbyViewModel(
  override val gameId: String,
  override val isHost: Boolean,
  override val allPlayers: List<String>,
) : LobbyView, ViewModel {
  override fun template(): String = "lobby"
}

data class WsLobbyViewModel(
  override val gameId: String,
  override val isHost: Boolean,
  override val allPlayers: List<String>,
) : LobbyView, ViewModel {
  override fun template(): String = "ws-lobby"
}

interface LobbyView {
  val gameId: String
  val isHost: Boolean
  val allPlayers: List<String>
}

data class GameViewModel(
  override val currentRound: Int,
  override val currentLivesCount: Int,
  override val currentThrowingStarsCount: Int,
  override val otherPlayers: List<OtherPlayer>,
  override val cards: List<Int>,
  override val isVotingToThrowStar: Boolean,
) : GameView, ViewModel {
  override fun template(): String = "game"
}

data class WsGameViewModel(
  override val currentRound: Int,
  override val currentLivesCount: Int,
  override val currentThrowingStarsCount: Int,
  override val otherPlayers: List<OtherPlayer>,
  override val cards: List<Int>,
  override val isVotingToThrowStar: Boolean,
) : GameView, ViewModel {
  override fun template(): String = "ws-game"
}

interface GameView {
  @Suppress("unused")
  val isInGame: Boolean get() = true
  val currentRound: Int
  val currentLivesCount: Int
  val currentThrowingStarsCount: Int
  val otherPlayers: List<OtherPlayer>
  val cards: List<Int>
  val isVotingToThrowStar: Boolean
}

object GameWonViewModel : ViewModel {
  override fun template(): String = "won"
}

object WsGameWonViewModel : ViewModel {
  override fun template(): String = "ws-won"
}

object GameLostViewModel : ViewModel {
  override fun template(): String = "lost"
}

object WsGameLostViewModel : ViewModel {
  override fun template(): String = "ws-lost"
}

data class PlayerLeftViewModel(
  override val playerThatLeftName: String,
) : PlayerLeftView, ViewModel {
  override fun template(): String = "player-left"
}

data class WsPlayerLeftViewModel(
  override val playerThatLeftName: String,
) : PlayerLeftView, ViewModel {
  override fun template(): String = "ws-player-left"
}

interface PlayerLeftView {
  val playerThatLeftName: String
}

class PebbleTemplateRenderer(
  private val engine: PebbleEngine =
    PebbleEngine.Builder()
      .cacheActive(false)
      .loader(FileLoader().apply { prefix = "src/main/resources" })
      .build(),
) : TemplateRenderer {
  override fun invoke(viewModel: ViewModel): String =
    try {
      val writer = StringWriter()
      engine.getTemplate(viewModel.template() + ".html")
        .evaluate(writer, mapOf("model" to viewModel))
      writer.toString()
    } catch (e: LoaderException) {
      throw RuntimeException("Template ${viewModel.template()} not found", e)
    }
}
