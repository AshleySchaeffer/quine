package com.thatdot.quine.persistor

import java.nio.file.Files
import java.util.Properties

import scala.concurrent.ExecutionContext

import org.apache.pekko.actor.CoordinatedShutdown

import org.apache.commons.io.FileUtils

class RocksDbPersistorSpec extends PersistenceAgentSpec {

  /** Tests should run if RocksDB could be started or if in CI (in CI, we want
    * to know if tests couldn't run).
    */
  override val runnable: Boolean = sys.env.contains("CI") || RocksDbPersistor.loadRocksDbLibrary()

  lazy val persistor: PrimePersistor =
    if (RocksDbPersistor.loadRocksDbLibrary()) {
      val f = Files.createTempDirectory("rocks.db")
      CoordinatedShutdown(system).addJvmShutdownHook(() => FileUtils.forceDelete(f.toFile))
      new RocksDbPrimePersistor(
        createParentDir = false,
        topLevelPath = f.toFile,
        writeAheadLog = true,
        syncWrites = false,
        dbOptionProperties = new Properties(),
        PersistenceConfig(),
        ioDispatcher = ExecutionContext.parasitic
      )
    } else {
      new StatelessPrimePersistor(PersistenceConfig(), None, new EmptyPersistor(_, _))
    }
}
