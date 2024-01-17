package com.thatdot.quine.persistor.cassandra

import scala.compat.ExecutionContexts
import scala.compat.java8.FutureConverters._
import scala.concurrent.Future

import org.apache.pekko.stream.Materializer

import cats.Applicative
import cats.implicits._
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.{PreparedStatement, SimpleStatement}

import com.thatdot.quine.persistor.cassandra.support._
import com.thatdot.quine.util.T2

trait MetaDataColumnName {
  import CassandraCodecs._
  final protected val keyColumn: CassandraColumn[String] = CassandraColumn("key")
  final protected val valueColumn: CassandraColumn[Array[Byte]] = CassandraColumn("value")
}

object MetaData extends TableDefinition with MetaDataColumnName {
  protected val tableName = "meta_data"
  protected val partitionKey: CassandraColumn[String] = keyColumn
  protected val clusterKeys = List.empty
  protected val dataColumns: List[CassandraColumn[Array[Byte]]] = List(valueColumn)

  private val createTableStatement: SimpleStatement = makeCreateTableStatement.build.setTimeout(createTableTimeout)

  private val selectAllStatement: SimpleStatement =
    select
      .columns(keyColumn.name, valueColumn.name)
      .build()

  private val selectSingleStatement: SimpleStatement =
    select
      .column(valueColumn.name)
      .where(keyColumn.is.eq)
      .build()

  private val deleteStatement: SimpleStatement =
    delete
      .where(keyColumn.is.eq)
      .build()
      .setIdempotent(true)

  def create(
    session: CqlSession,
    verifyTable: String => Future[Unit],
    readSettings: CassandraStatementSettings,
    writeSettings: CassandraStatementSettings,
    shouldCreateTables: Boolean
  )(implicit
    mat: Materializer,
    futureInstance: Applicative[Future]
  ): Future[MetaData] = {
    import shapeless.syntax.std.tuple._
    logger.debug("Preparing statements for {}", tableName)

    val createdSchema = futureInstance.whenA(shouldCreateTables)(
      session
        .executeAsync(createTableStatement)
        .toScala
        .flatMap(_ => verifyTable(tableName))(ExecutionContexts.parasitic)
    )

    createdSchema.flatMap(_ =>
      (
        T2(insertStatement, deleteStatement).map(prepare(session, writeSettings)).toTuple ++
        T2(selectAllStatement, selectSingleStatement).map(prepare(session, readSettings)).toTuple
      ).mapN(new MetaData(session, _, _, _, _))
    )(ExecutionContexts.parasitic)
  }
}

class MetaData(
  session: CqlSession,
  insertStatement: PreparedStatement,
  deleteStatement: PreparedStatement,
  selectAllStatement: PreparedStatement,
  selectSingleStatement: PreparedStatement
)(implicit mat: Materializer)
    extends CassandraTable(session)
    with MetaDataColumnName {

  import syntax._

  def getMetaData(key: String): Future[Option[Array[Byte]]] =
    queryOne(
      selectSingleStatement.bindColumns(keyColumn.set(key)),
      valueColumn
    )

  def getAllMetaData(): Future[Map[String, Array[Byte]]] =
    selectColumns(selectAllStatement.bind(), keyColumn, valueColumn)

  def setMetaData(key: String, newValue: Option[Array[Byte]]): Future[Unit] =
    executeFuture(
      newValue match {
        case None => deleteStatement.bindColumns(keyColumn.set(key))
        case Some(value) => insertStatement.bindColumns(keyColumn.set(key), valueColumn.set(value))
      }
    )

  def nonEmpty(): Future[Boolean] = yieldsResults(MetaData.firstRowStatement)
}
