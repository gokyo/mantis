package io.iohk.ethereum.blockchain.sync.regular

import io.iohk.ethereum.metrics.MetricsContainer

import scala.concurrent.duration.{MILLISECONDS, NANOSECONDS}

object RegularSyncMetrics extends MetricsContainer {
  private final val blockPropagationTimer = "regularsync.blocks.propagation.timer"

  final val MinedBlockPropagationTimer = metrics.timer(blockPropagationTimer, "class", "MinedBlockPropagation")
  final val CheckpointBlockPropagationTimer =
    metrics.timer(blockPropagationTimer, "class", "CheckpointBlockPropagation")
  final val NewBlockPropagationTimer = metrics.timer(blockPropagationTimer, "class", "NewBlockPropagation")
  final val DefaultBlockPropagationTimer = metrics.timer(blockPropagationTimer, "class", "DefaultBlockPropagation")

  def recordMinedBlockPropagationTimer(time: Long): Unit = MinedBlockPropagationTimer.record(time, NANOSECONDS)
  def recordImportCheckpointPropagationTimer(time: Long): Unit =
    CheckpointBlockPropagationTimer.record(time, NANOSECONDS)
  def recordImportNewBlockPropagationTimer(time: Long): Unit = NewBlockPropagationTimer.record(time, NANOSECONDS)
  def recordDefaultBlockPropagationTimer(time: Long): Unit = DefaultBlockPropagationTimer.record(time, NANOSECONDS)
}
