package uk.co.rafearnold.themind

import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("Main")

fun main() {
  startServer(GameConfig(12, 3, 1)).block()
}
