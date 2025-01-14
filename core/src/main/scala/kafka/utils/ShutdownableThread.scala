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

package kafka.utils

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.apache.kafka.common.internals.FatalExitError

/**
 * 可停止线程，继承 jdk 的 Thread 类，
 * 该类重写了 thread 的 run 方法，不再直接通过 Thread 构造方法传递 Runnable 实例来执行该 Runnable 实例
 * 和常规的 new Thread(new Runnable() {}).start() 玩法不一样，具体看重写的 run 方法
 */
abstract class ShutdownableThread(val name: String, val isInterruptible: Boolean = true)
        extends Thread(name) with Logging {
  this.setDaemon(false)
  this.logIdent = "[" + name + "]: "
  private val shutdownInitiated = new CountDownLatch(1)
  private val shutdownComplete = new CountDownLatch(1)
  @volatile private var isStarted: Boolean = false
  
  def shutdown(): Unit = {
    initiateShutdown()
    awaitShutdown()
  }

  def isShutdownInitiated: Boolean = shutdownInitiated.getCount == 0

  def isShutdownComplete: Boolean = shutdownComplete.getCount == 0

  /**
    * @return true if there has been an unexpected error and the thread shut down
    */
  // mind that run() might set both when we're shutting down the broker
  // but the return value of this function at that point wouldn't matter
  def isThreadFailed: Boolean = isShutdownComplete && !isShutdownInitiated

  def initiateShutdown(): Boolean = {
    this.synchronized {
      if (isRunning) {
        info("Shutting down")
        shutdownInitiated.countDown()
        if (isInterruptible)
          interrupt()
        true
      } else
        false
    }
  }

  /**
   * After calling initiateShutdown(), use this API to wait until the shutdown is complete
   */
  def awaitShutdown(): Unit = {
    if (!isShutdownInitiated)
      throw new IllegalStateException("initiateShutdown() was not called before awaitShutdown()")
    else {
      if (isStarted)
        shutdownComplete.await()
      info("Shutdown completed")
    }
  }

  /**
   *  Causes the current thread to wait until the shutdown is initiated,
   *  or the specified waiting time elapses.
   *
   * @param timeout
   * @param unit
   */
  def pause(timeout: Long, unit: TimeUnit): Unit = {
    if (shutdownInitiated.await(timeout, unit))
      trace("shutdownInitiated latch count reached zero. Shutdown called.")
  }

  /**
   * This method is repeatedly invoked until the thread shuts down or this method throws an exception
   */
  def doWork(): Unit

  // 重写 run 方法
  override def run(): Unit = {
    isStarted = true
    info("Starting")
    try {
      // 如果该线程正在运行，调用 doWork() 方法
      // 这种方式其实就是一直循环不断的执行任务
      while (isRunning)
        doWork()
    } catch {
      case e: FatalExitError =>
        shutdownInitiated.countDown()
        shutdownComplete.countDown()
        info("Stopped")
        Exit.exit(e.statusCode())
      case e: Throwable =>
        if (isRunning)
          error("Error due to", e)
    } finally {
       shutdownComplete.countDown()
    }
    info("Stopped")
  }

  // 通过判断当前实例的的关闭已经初始化标识是否为 false 来判断当前线程是否停止
  def isRunning: Boolean = !isShutdownInitiated
}
