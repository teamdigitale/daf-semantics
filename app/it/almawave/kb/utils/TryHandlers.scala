package it.almawave.kb.utils

import scala.util.Try
import scala.util.Failure
import scala.util.Success
import org.slf4j.Logger
import org.eclipse.rdf4j.repository.RepositoryConnection
import org.eclipse.rdf4j.repository.sail.SailRepository
import org.eclipse.rdf4j.sail.memory.MemoryStore
import org.eclipse.rdf4j.repository.Repository
import java.io.PrintWriter
import java.util.ArrayList
import scala.collection.mutable.ListBuffer
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.slf4j.LoggerFactory

object TryHandlers {

  import org.slf4j.Logger

  /*
   * SEE:
   * https://stackoverflow.com/questions/2395984/scala-using-function
   * 
   * ex: 
   * using(new PrintWriter("sample.txt")){ out => 
   * 	out.println("hellow world!")
   * }
   * 
   */
  def using[A, B <: { def close(): Unit }](closeable: B)(action: B => A): A = {
    try {
      action(closeable)
    } finally {
      closeable.close()
    }
  }

  // IDEA
  def logErrors[A](logger: Logger)(action: Logger => A)(implicit msg: String = ""): Try[A] = {

    Try {
      action(logger)
    } match {
      case Success(s) =>
        Success(s)
      case Failure(ex) =>
        logger.debug(s"${msg}\n${ex}")
        Failure(ex)
    }

  }

  // REVIEW: try using this method for avoiding complexity in transactions...
  def doInTransaction[A, C <: { def begin(): Unit; def commit(): Unit; def rollback(): Unit }](connection: C)(action: C => A): Try[A] = {

    connection.begin()
    Try {
      action(connection)
    } match {
      case Success(s) =>
        connection.commit()
        Success(s)
      case Failure(f) =>
        connection.rollback()
        Failure(f)
    }

  }

  implicit class TryHasFinally[T](val value: Try[T]) extends AnyVal {

    import scala.util.control.NonFatal

    def Finally(action: => Unit): Try[T] =
      try {
        action
        value
      } catch {
        case NonFatal(cause) => Failure[T](cause)
      }
  }

  /**
   * this object could be used for simplyfing handling of loggin/exceptions while getting results of an operation
   * if a logger is already used in the caller context, it will be used
   */
  object TryLog {

    def apply[X](block: => X)(msg_err: String)(implicit logger: Logger): Try[X] = {

      try {

        Success { block }

      } catch {

        case ex: Throwable =>
          logger.error(s"${msg_err}\n${ex}")
          Failure(ex)

      }

    }
  }

  /*
   * this could be useful for simplifying code: 
   * 	+ default connection handling (open/close)
   * 	+ default transaction handling
   */
  object RepositoryAction {

    def apply[R](repo: Repository)(conn_action: (RepositoryConnection => Any))(msg_err: String)(implicit logger: Logger) = {

      // NOTE: we could imagine using a connection pool here
      val _conn = repo.getConnection

      _conn.begin()

      val results: Try[R] = try {

        val success = Success(conn_action(_conn))
        _conn.commit()
        success.asInstanceOf[Try[R]]

      } catch {

        case ex: Throwable =>
          val failure = Failure(ex)
          _conn.rollback()
          logger.info(msg_err)
          failure

      }

      _conn.close()

      results
    }

  }

}

object MainExamples extends App {

  import it.almawave.kb.utils.TryHandlers._
  import play.Logger

  //  val logger = LoggerFactory.getLogger(this.getClass)
  val logger = Logger.underlying()

  using(new PrintWriter("sample.txt")) { out =>
    out.println("hellow world!")
  }

  logErrors(logger) {
    logger =>
      println("vediamo...")
  }

  val repo = new MemoryStore()
  repo.initialize()

  using(repo.getConnection) {

    conn =>
      // IDEA...
      doInTransaction(conn) {
        conn =>
          val vf = SimpleValueFactory.getInstance
          conn.addStatement(vf.createIRI("http://sub_01"), vf.createIRI("http://prp_01"), vf.createLiteral("obj_01"))
      }

  }

  // IDEA...
  val elements = using(repo.getConnection) { conn =>
    val list = new ListBuffer[Statement]
    val results = conn.getStatements(null, null, null, false)
    while (results.hasNext()) {
      list += results.next()
    }
    list.toStream
  }
  println(elements.mkString("|"))

  repo.shutDown()

}