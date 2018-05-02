package benchmarks

/*
 * Copyright 2018, Aeneas Platform.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.concurrent.TimeUnit

import bench_preparings.NonActorMiner
import com.typesafe.config.ConfigFactory
import commons.SimpleBoxTransactionGenerator
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import scorex.core.utils.ScorexLogging
import settings.AeneasSettings
import wallet.AeneasWallet

/**
  * @author is Alex Syrotenko (@flystyle)
  *         Created on 17.04.18.
  */
//noinspection TypeAnnotation

case class LoadSettings() extends ScorexLogging {
   val simpleSettings : AeneasSettings = AeneasSettings.read()
   private val root = ConfigFactory.load()
   val aeneasActor = root.getConfig("Aeneas")
   log.debug(aeneasActor.toString)
   // set logging path:
   sys.props += ("log.dir" -> simpleSettings.scorexSettings.logDir.getAbsolutePath)
}

@State(Scope.Benchmark)
class MinerMedian {
   lazy val settings = AeneasSettings.read().miningSettings

   var transactionGenerator : SimpleBoxTransactionGenerator = _
   var miner : NonActorMiner = _

   @Setup(Level.Trial)
   def setup = {
      transactionGenerator = new SimpleBoxTransactionGenerator(AeneasWallet.readOrGenerate(AeneasSettings.read().scorexSettings))
      miner = new NonActorMiner(settings, transactionGenerator)
      Thread.sleep(250)
   }

   @BenchmarkMode(Array(Mode.AverageTime))
   @Fork(1)
   @Threads(1)
   @Warmup(iterations = 3, time = 75, timeUnit = TimeUnit.SECONDS, batchSize = 1)
   @Measurement(iterations = 10, time = 75, timeUnit = TimeUnit.SECONDS, batchSize = 1)
   @Benchmark
   def averageMiningTime(bh: Blackhole): Unit = {
      val result = miner.beginMining(20 << 20)
      bh.consume(result)
   }
}



