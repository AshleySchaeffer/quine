package com.thatdot.quine.persistor

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

import com.codahale.metrics.NoopMetricRegistry

import com.thatdot.common.logging.Log.LogConfig
import com.thatdot.quine.util.FromSingleExecutionContext

class MapDbPersistorSpec(implicit protected val logConfig: LogConfig) extends PersistenceAgentSpec {

  lazy val persistor: TempMapDbPrimePersistor =
    new TempMapDbPrimePersistor(
      writeAheadLog = false,
      numberPartitions = 1,
      commitInterval = 1.second, // NB this is unused while `writeAheadLog = false
      metricRegistry = new NoopMetricRegistry(),
      persistenceConfig = PersistenceConfig(),
      bloomFilterSize = None,
      ExecutionContext = new FromSingleExecutionContext(ExecutionContext.parasitic),
    )
}
