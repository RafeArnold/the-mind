package uk.co.rafearnold.themind

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.FileLoader
import org.http4k.template.TemplateRenderer
import org.http4k.template.ViewModel
import org.http4k.template.ViewNotFound
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
  override val currentLivesCount: Int,
  override val currentThrowingStarsCount: Int,
  override val cards: List<Int>,
  override val playersVotingToThrowStar: Collection<String>,
) : GameView, ViewModel {
  override fun template(): String = "game"
}

data class WsGameViewModel(
  override val currentLivesCount: Int,
  override val currentThrowingStarsCount: Int,
  override val cards: List<Int>,
  override val playersVotingToThrowStar: Collection<String>,
) : GameView, ViewModel {
  override fun template(): String = "ws-game"
}

interface GameView {
  val currentLivesCount: Int
  val currentThrowingStarsCount: Int
  val cards: List<Int>
  val playersVotingToThrowStar: Collection<String>
}

object WsGameWonModel : ViewModel {
  override fun template(): String = "ws-won"
}

object WsGameLostModel : ViewModel {
  override fun template(): String = "ws-lost"
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
      throw ViewNotFound(viewModel)
    }
}
