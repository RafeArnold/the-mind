package uk.co.rafearnold.themind

import org.sqids.Sqids

interface GameIdGenerator {
  fun nextId(): String
}

class SqidsGameIdGenerator : GameIdGenerator {

  private val sqids: Sqids =
    Sqids.builder().alphabet("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".shuffled()).minLength(2).build()

  private var nextId: Long = 0

  override fun nextId(): String = sqids.encode(listOf(nextId++))
}

private fun String.shuffled(): String = toCharArray().apply { shuffle() }.concatToString()
