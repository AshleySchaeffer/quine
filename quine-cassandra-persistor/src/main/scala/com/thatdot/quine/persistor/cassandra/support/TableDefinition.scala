package com.thatdot.quine.persistor.cassandra.support

import java.time.Duration

import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

import org.apache.pekko.stream.Materializer

import cats.Applicative
import com.datastax.oss.driver.api.core.cql.{AsyncCqlSession, AsyncResultSet, PreparedStatement, SimpleStatement}
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.{bindMarker, deleteFrom, insertInto, selectFrom}
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder.{createTable, dropTable}
import com.datastax.oss.driver.api.querybuilder.delete.DeleteSelection
import com.datastax.oss.driver.api.querybuilder.schema.CreateTable
import com.datastax.oss.driver.api.querybuilder.select.SelectFrom
import com.typesafe.scalalogging.LazyLogging

import com.thatdot.quine.graph.NamespaceId
import com.thatdot.quine.persistor.cassandra.Chunker

abstract class TableDefinition[A](unqualifiedTableName: String, namespace: NamespaceId) extends LazyLogging {

  def create(
    session: CqlSession,
    chunker: Chunker,
    readSettings: CassandraStatementSettings,
    writeSettings: CassandraStatementSettings
  )(implicit materializer: Materializer, futureInstance: Applicative[Future]): Future[A]

  protected val tableName: CqlIdentifier =
    CqlIdentifier.fromCql(namespace.fold("")(_.name + "_") + unqualifiedTableName)

  protected def partitionKey: CassandraColumn[_]
  protected def clusterKeys: List[CassandraColumn[_]]
  protected def dataColumns: List[CassandraColumn[_]]

  protected def prepare(session: AsyncCqlSession, settings: CassandraStatementSettings)(
    statement: SimpleStatement
  ): Future[PreparedStatement] = {
    logger.trace("Preparing {}", statement.getQuery)
    session.prepareAsync(settings(statement)).toScala
  }

  /** Start building a CREATE TABLE statement, based on the {{{partitionKey}}}, {{{clusterKeys}}}, and {{{dataColumns}}}
    * specified. Set any other desired options (e.g. {{{.withClusteringOrder}}}) and then call {{{.build()}}} to
    * get a CQL statement to execute.
    * @return a CreateTable builder
    */
  final protected def makeCreateTableStatement: CreateTable = {
    val createKeys: CreateTable = clusterKeys.foldLeft(
      createTable(tableName).ifNotExists.withPartitionKey(partitionKey.name, partitionKey.cqlType)
    )((t, c) => t.withClusteringColumn(c.name, c.cqlType))
    dataColumns.foldLeft(createKeys)((t, c) => t.withColumn(c.name, c.cqlType))
  }

  protected val ddlTimeout: Duration = Duration.ofSeconds(12)

  protected val createTableStatement: SimpleStatement
  def executeCreateTable(session: AsyncCqlSession, verifyCreated: CqlIdentifier => Future[Unit])(implicit
    ec: ExecutionContext
  ): Future[Unit] =
    session.executeAsync(createTableStatement).toScala.flatMap(_ => verifyCreated(tableName))(ec)

  protected def select: SelectFrom = selectFrom(tableName)
  protected def delete: DeleteSelection = deleteFrom(tableName)

  // The head of the list looks needlessly special-cased. That's just type-safety in the Cassandra Query Builder's preventing you from constructing an INSERT
  // statement with no values inserted. We could bypass it by casting the insertInto(tableName) to RegularInsert. Or we could just go with the types.
  // This requires a non-empty list of columns to insert.
  // The first element is the partition key, anyways - could just treat that separately, and skip the non-empty list

  /** Make an insert statement using all the configured columns of the table.
    * It's marked as idempotent, as it is believed all INSERTs of this form will be, and
    * this statement is not modifiable / customizable after creation.
    * @return An ordinary CQL statement (preparing it with some bind markers is suggested)
    */
  protected def insertStatement: SimpleStatement = (clusterKeys ++ dataColumns)
    .foldLeft(
      insertInto(tableName).value(partitionKey.name, bindMarker(partitionKey.name))
    )((s, c) => s.value(c.name, bindMarker(c.name)))
    .build
    .setIdempotent(true)

  // Used to delete all entries with a particular Quine Id, pretty much
  protected def deleteAllByPartitionKeyStatement: SimpleStatement = delete.where(partitionKey.is.eq).build

  /** Gets the first row from this table
    * @return an ordinary CQL statement to get a single row from this table, if any exists.
    */
  def firstRowStatement: SimpleStatement = select.column(partitionKey.name).limit(1).build

  def dropTableStatement: SimpleStatement = dropTable(tableName).ifExists.build.setTimeout(ddlTimeout)
}
