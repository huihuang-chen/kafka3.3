/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.server.metadata

import java.util
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.function.Consumer
import kafka.metrics.KafkaMetricsGroup
import org.apache.kafka.image.{MetadataDelta, MetadataImage}
import org.apache.kafka.common.utils.{LogContext, Time}
import org.apache.kafka.queue.{EventQueue, KafkaEventQueue}
import org.apache.kafka.raft.{Batch, BatchReader, LeaderAndEpoch, RaftClient}
import org.apache.kafka.server.common.ApiMessageAndVersion
import org.apache.kafka.server.fault.FaultHandler
import org.apache.kafka.snapshot.SnapshotReader

import java.util.concurrent.atomic.AtomicBoolean


object BrokerMetadataListener {
  val MetadataBatchProcessingTimeUs = "MetadataBatchProcessingTimeUs"
  val MetadataBatchSizes = "MetadataBatchSizes"
}

/** 集群元数据监听器，该监听器会被注册到 kafkaRaftClient 中 */
class BrokerMetadataListener(
  val brokerId: Int,
  time: Time,
  threadNamePrefix: Option[String],
  val maxBytesBetweenSnapshots: Long,
  val snapshotter: Option[MetadataSnapshotter],
  brokerMetrics: BrokerServerMetrics,
  _metadataLoadingFaultHandler: FaultHandler
) extends RaftClient.Listener[ApiMessageAndVersion] with KafkaMetricsGroup {

  private val metadataFaultOccurred = new AtomicBoolean(false)
  private val metadataLoadingFaultHandler: FaultHandler = new FaultHandler() {
    override def handleFault(failureMessage: String, cause: Throwable): RuntimeException = {
      // If the broker has any kind of error handling metadata records or publishing a new image
      // we will disable taking new snapshots in order to preserve the local metadata log. Once we
      // encounter a metadata processing error, the broker will be in an undetermined state.
      if (metadataFaultOccurred.compareAndSet(false, true)) {
        error("Disabling metadata snapshots until this broker is restarted.")
      }
      _metadataLoadingFaultHandler.handleFault(failureMessage, cause)
    }
  }

  private val logContext = new LogContext(s"[BrokerMetadataListener id=$brokerId] ")
  private val log = logContext.logger(classOf[BrokerMetadataListener])
  logIdent = logContext.logPrefix()

  /**
   * A histogram tracking the time in microseconds it took to process batches of events.
   */
  private val batchProcessingTimeHist = newHistogram(BrokerMetadataListener.MetadataBatchProcessingTimeUs)

  /**
   * A histogram tracking the sizes of batches that we have processed.
   */
  private val metadataBatchSizeHist = newHistogram(BrokerMetadataListener.MetadataBatchSizes)

  /**
   * The highest metadata offset that we've seen.  Written only from the event queue thread.
   */
  @volatile var _highestOffset = -1L

  /**
   * The highest metadata log time that we've seen. Written only from the event queue thread.
   */
  private var _highestTimestamp = -1L

  /**
   * The current broker metadata image. Accessed only from the event queue thread.
   */
  private var _image = MetadataImage.EMPTY

  /**
   * The current metadata delta. Accessed only from the event queue thread.
   */
  private var _delta = new MetadataDelta(_image)

  /**
   * The object to use to publish new metadata changes, or None if this listener has not
   * been activated yet. Accessed only from the event queue thread.
   */
  private var _publisher: Option[MetadataPublisher] = None

  /**
   * The number of bytes of records that we have read  since the last snapshot we took.
   * This does not include records we read from a snapshot.
   * Accessed only from the event queue thread.
   */
  private var _bytesSinceLastSnapshot: Long = 0L

  /**
   * The event queue which runs this listener.
   */
  /** 事件队列，注意这个队列内置了事件处理器并且持有一个事件处理线程，相当于在使用这个队列的时候只需要向这个队列入队事件就行了 */
  val eventQueue = new KafkaEventQueue(time, logContext, threadNamePrefix.getOrElse(""))

  /**
   * Returns the highest metadata-offset. Thread-safe.
   */
  def highestMetadataOffset: Long = _highestOffset

  /**
   * Handle new metadata records.
   */
  /** kafkaRaftClient 在发出 FETCH 请求后收到 controller leader 回复的响应有新的集群元数据日志传过来，则会触发该函数 */
  override def handleCommit(reader: BatchReader[ApiMessageAndVersion]): Unit = {
    // 向事件队里中追加一个 HandleCommitEvent，队列会自己调用该 HandleCommitEvent 的 run 方法
    eventQueue.append(new HandleCommitsEvent(reader))
  }

  class HandleCommitsEvent(reader: BatchReader[ApiMessageAndVersion])
      extends EventQueue.FailureLoggingEvent(log) {
    override def run(): Unit = {
      val results = try {
        val loadResults = loadBatches(_delta, reader, None, None, None, None)
        if (isDebugEnabled) {
          debug(s"Loaded new commits: $loadResults")
        }
        loadResults
      } catch {
        case e: Throwable =>
          metadataLoadingFaultHandler.handleFault(s"Unable to load metadata commits " +
            s"from the BatchReader starting at base offset ${reader.baseOffset()}", e)
          return
      } finally {
        reader.close()
      }

      _bytesSinceLastSnapshot = _bytesSinceLastSnapshot + results.numBytes
      if (shouldSnapshot()) {
        maybeStartSnapshot()
      }

      // 对所有的发布者调用 publish 方法处理元数据改变需要处理的逻辑
      _publisher.foreach(publish)
    }
  }

  private def shouldSnapshot(): Boolean = {
    (_bytesSinceLastSnapshot >= maxBytesBetweenSnapshots) || metadataVersionChanged()
  }

  private def metadataVersionChanged(): Boolean = {
    // The _publisher is empty before starting publishing, and we won't compute feature delta
    // until we starting publishing
    _publisher.nonEmpty && Option(_delta.featuresDelta()).exists { featuresDelta =>
      featuresDelta.metadataVersionChange().isPresent
    }
  }

  private def maybeStartSnapshot(): Unit = {
    snapshotter.foreach { snapshotter =>
      if (metadataFaultOccurred.get()) {
        trace("Not starting metadata snapshot since we previously had an error")
      } else if (snapshotter.maybeStartSnapshot(_highestTimestamp, _delta.apply())) {
        _bytesSinceLastSnapshot = 0L
      }
    }
  }

  /**
   * Handle metadata snapshots
   */
  override def handleSnapshot(reader: SnapshotReader[ApiMessageAndVersion]): Unit =
    eventQueue.append(new HandleSnapshotEvent(reader))

  class HandleSnapshotEvent(reader: SnapshotReader[ApiMessageAndVersion])
    extends EventQueue.FailureLoggingEvent(log) {
    override def run(): Unit = {
      val snapshotName = s"${reader.snapshotId().offset}-${reader.snapshotId().epoch}"
      try {
        info(s"Loading snapshot ${snapshotName}")
        _delta = new MetadataDelta(_image) // Discard any previous deltas.
        val loadResults = loadBatches(_delta,
          reader,
          Some(reader.lastContainedLogTimestamp),
          Some(reader.lastContainedLogOffset),
          Some(reader.lastContainedLogEpoch),
          Some(snapshotName))
        try {
          _delta.finishSnapshot()
        } catch {
          case e: Throwable => metadataLoadingFaultHandler.handleFault(
              s"Error finishing snapshot ${snapshotName}", e)
        }
        info(s"Loaded snapshot ${snapshotName}: ${loadResults}")
      } catch {
        case t: Throwable => metadataLoadingFaultHandler.handleFault("Uncaught exception while " +
          s"loading broker metadata from Metadata snapshot ${snapshotName}", t)
      } finally {
        reader.close()
      }
      _publisher.foreach(publish)
    }
  }

  case class BatchLoadResults(numBatches: Int, numRecords: Int, elapsedUs: Long, numBytes: Long) {
    override def toString: String = {
      s"$numBatches batch(es) with $numRecords record(s) in $numBytes bytes " +
        s"ending at offset $highestMetadataOffset in $elapsedUs microseconds"
    }
  }

  /**
   * Load and replay the batches to the metadata delta.
   *
   * When loading and replay a snapshot the appendTimestamp and snapshotId parameter should be provided.
   * In a snapshot the append timestamp, offset and epoch reported by the batch is independent of the ones
   * reported by the metadata log.
   *
   * @param delta metadata delta on which to replay the records
   * @param iterator sequence of metadata record bacthes to replay
   * @param lastAppendTimestamp optional append timestamp to use instead of the batches timestamp
   * @param lastCommittedOffset optional offset to use instead of the batches offset
   * @param lastCommittedEpoch optional epoch to use instead of the batches epoch
   */
  private def loadBatches(
    delta: MetadataDelta,
    iterator: util.Iterator[Batch[ApiMessageAndVersion]],
    lastAppendTimestamp: Option[Long],
    lastCommittedOffset: Option[Long],
    lastCommittedEpoch: Option[Int],
    snapshotName: Option[String]
  ): BatchLoadResults = {
    val startTimeNs = time.nanoseconds()
    var numBatches = 0
    var numRecords = 0
    var numBytes = 0L

    while (iterator.hasNext) {
      val batch = iterator.next()

      val epoch = lastCommittedEpoch.getOrElse(batch.epoch())
      _highestTimestamp = lastAppendTimestamp.getOrElse(batch.appendTimestamp())

      var index = 0
      batch.records().forEach { messageAndVersion =>
        if (isTraceEnabled) {
          trace(s"Metadata batch ${batch.lastOffset}: processing [${index + 1}/${batch.records.size}]:" +
            s" ${messageAndVersion.message}")
        }
        _highestOffset = lastCommittedOffset.getOrElse(batch.baseOffset() + index)
        try {
          delta.replay(highestMetadataOffset, epoch, messageAndVersion.message())
        } catch {
          case e: Throwable => snapshotName match {
            case None => metadataLoadingFaultHandler.handleFault(
              s"Error replaying metadata log record at offset ${_highestOffset}", e)
            case Some(name) => metadataLoadingFaultHandler.handleFault(
              s"Error replaying record ${index} from snapshot ${name} at offset ${_highestOffset}", e)
          }
        } finally {
          numRecords += 1
          index += 1
        }
      }
      numBytes = numBytes + batch.sizeInBytes()
      metadataBatchSizeHist.update(batch.records().size())
      numBatches = numBatches + 1
    }

    val endTimeNs = time.nanoseconds()
    val elapsedUs = TimeUnit.MICROSECONDS.convert(endTimeNs - startTimeNs, TimeUnit.NANOSECONDS)
    batchProcessingTimeHist.update(elapsedUs)
    BatchLoadResults(numBatches, numRecords, elapsedUs, numBytes)
  }

  def startPublishing(publisher: MetadataPublisher): CompletableFuture[Void] = {
    val event = new StartPublishingEvent(publisher)
    eventQueue.append(event)
    event.future
  }

  class StartPublishingEvent(publisher: MetadataPublisher)
      extends EventQueue.FailureLoggingEvent(log) {
    val future = new CompletableFuture[Void]()

    override def run(): Unit = {
      _publisher = Some(publisher)
      log.info(s"Starting to publish metadata events at offset $highestMetadataOffset.")
      try {
        if (metadataVersionChanged()) {
          maybeStartSnapshot()
        }
        publish(publisher)
        future.complete(null)
      } catch {
        case e: Throwable =>
          future.completeExceptionally(e)
          throw e
      }
    }
  }

  // This is used in tests to alter the publisher that is in use by the broker.
  def alterPublisher(publisher: MetadataPublisher): CompletableFuture[Void] = {
    val event = new AlterPublisherEvent(publisher)
    eventQueue.append(event)
    event.future
  }

  class AlterPublisherEvent(publisher: MetadataPublisher)
    extends EventQueue.FailureLoggingEvent(log) {
    val future = new CompletableFuture[Void]()

    override def run(): Unit = {
      _publisher = Some(publisher)
      log.info(s"Set publisher to ${publisher}")
      future.complete(null)
    }
  }

  private def publish(publisher: MetadataPublisher): Unit = {
    val delta = _delta
    try {
      // 这里会根据本次监听到的元数据更新信息，重新生成一个完整的 metadataImage
      _image = _delta.apply()
    } catch {
      case t: Throwable =>
        // If we cannot apply the delta, this publish event will throw and we will not publish a new image.
        // The broker will continue applying metadata records and attempt to publish again.
        throw metadataLoadingFaultHandler.handleFault(s"Error applying metadata delta $delta", t)
    }

    // 将 metadataImage 包装成 metadataDelta
    _delta = new MetadataDelta(_image)
    if (isDebugEnabled) {
      debug(s"Publishing new metadata delta $delta at offset ${_image.highestOffsetAndEpoch().offset}.")
    }

    // This publish call is done with its own try-catch and fault handler
    /** 调用发布者的 publish 方法，上面只是更新了集群元数据，但是还没有更新具体功能，
     * 这里就是将元数据更改的部分分发到对应具体功能进行具体的处理
     * 定位到 {@link BrokerMetadataPublisher.publish()} 看 publisher 的处理逻辑 */
    publisher.publish(delta, _image)

    // Update the metrics since the publisher handled the lastest image
    brokerMetrics.lastAppliedRecordOffset.set(_highestOffset)
    brokerMetrics.lastAppliedRecordTimestamp.set(_highestTimestamp)
  }

  override def handleLeaderChange(leaderAndEpoch: LeaderAndEpoch): Unit = {
    // Nothing to do.
  }

  override def beginShutdown(): Unit = {
    eventQueue.beginShutdown("beginShutdown", new ShutdownEvent())
  }

  class ShutdownEvent extends EventQueue.FailureLoggingEvent(log) {
    override def run(): Unit = {
      brokerMetrics.close()
      removeMetric(BrokerMetadataListener.MetadataBatchProcessingTimeUs)
      removeMetric(BrokerMetadataListener.MetadataBatchSizes)
    }
  }

  def close(): Unit = {
    beginShutdown()
    eventQueue.close()
  }

  // VisibleForTesting
  private[kafka] def getImageRecords(): CompletableFuture[util.List[ApiMessageAndVersion]] = {
    val future = new CompletableFuture[util.List[ApiMessageAndVersion]]()
    eventQueue.append(new GetImageRecordsEvent(future))
    future
  }

  class GetImageRecordsEvent(future: CompletableFuture[util.List[ApiMessageAndVersion]])
      extends EventQueue.FailureLoggingEvent(log) with Consumer[util.List[ApiMessageAndVersion]] {
    val records = new util.ArrayList[ApiMessageAndVersion]()
    override def accept(batch: util.List[ApiMessageAndVersion]): Unit = {
      records.addAll(batch)
    }

    override def run(): Unit = {
      _image.write(this)
      future.complete(records)
    }
  }
}
