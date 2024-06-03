package com.thatdot.quine.graph.cypher

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

/** Packages together all the information about a query that is running
  *
  * @param compiled       the query that produced these results: note that the starting location of the query is left
  *                       generic, as it does not matter for the query's results
  * @param resultSource the underlying Source of QueryContexts (rows) emitted by the query
  */
final case class RunningCypherQuery(
  compiled: CompiledQuery[Location],
  private val resultSource: Source[QueryContext, NotUsed]
) {

  /** Ordered variables returned by the query */
  def columns: Vector[Symbol] = compiled.query.columns match {
    case Columns.Specified(cols) => cols
    case Columns.Omitted =>
      throw new IllegalArgumentException(
        "Missing column information for query"
      )
  }

  /** Results, in the same order as [[columns]] */
  def results: Source[Vector[Value], NotUsed] =
    resultSource.map { (context: QueryContext) =>
      columns.map(context.getOrElse(_, Expr.Null))
    }

//  @deprecated("Use `results` instead", "soon!")
//  def contexts: Source[QueryContext, NotUsed] = resultSource
}
