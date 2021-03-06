package org.reactivecouchbase.client

import net.spy.memcached.internal._
import net.spy.memcached.CASValue
import scala.concurrent.{ Promise, Future, ExecutionContext }
import com.couchbase.client.internal.{ HttpCompletionListener, HttpFuture }
import net.spy.memcached.ops.OperationStatus
import play.api.libs.json.Reads
import org.reactivecouchbase.CouchbaseBucket

/**
 * Internal API to deal with Java Drivers Future
 */
private[reactivecouchbase] object CouchbaseFutures {

  /**
   *
   * Transform an BulkFuture to a Future[Map]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @return
   */
  def waitForBulkRaw(future: BulkFuture[java.util.Map[String, AnyRef]], b: CouchbaseBucket, ec : ExecutionContext): Future[java.util.Map[String, AnyRef]] = {
    val promise = Promise[java.util.Map[String, AnyRef]]()
    future.addListener(new BulkGetCompletionListener() {
      def onComplete(f: BulkGetFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled || f.isTimeout) {
            promise.success(f.get().asInstanceOf[java.util.Map[String, AnyRef]])
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"BulkFuture epic fail !!! ${f.isDone} : ${f.isCancelled} : ${f.isTimeout}"))
            else {
              b.driver.logger.warn(s"BulkFuture not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.get().asInstanceOf[java.util.Map[String, AnyRef]])
            }
          }
        }
      }
    })
    promise.future
  }

  /**
   *
   * Transform an GetFuture to a Future[T]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @tparam T internal type
   * @return
   */
  def waitForGet[T](future: GetFuture[T], b: CouchbaseBucket, ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    future.addListener(new GetCompletionListener() {
      def onComplete(f: GetFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled) {
            promise.success(f.get().asInstanceOf[T])
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"GetFuture epic fail !!! ${f.isDone} : ${f.isCancelled}"))
            else {
              b.driver.logger.warn(s"GetFuture not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.get().asInstanceOf[T])
            }
          }
        }
      }
    })
    promise.future
  }

  /**
   *
   * @param opstat the operation status
   */
  class OperationStatusError(val opstat: OperationStatus) extends ReactiveCouchbaseException("OperationStatusError", opstat.getMessage)

  /**
   *
   * @param opstat the operation status
   */
  class OperationStatusErrorNotFound(val opstat: OperationStatus) extends ReactiveCouchbaseException("OperationStatusErrorNotFound", opstat.getMessage)

  /**
   *
   * @param opstat the operation status
   */
  class OperationStatusErrorIsLocked(val opstat: OperationStatus) extends ReactiveCouchbaseException("OperationStatusErrorIsLocked", opstat.getMessage)

  /**
   *
   * Transform an OperationFuture to a Future[CasValue[T]]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @param r
   * @tparam T internal type
   * @return
   */
  def waitForGetAndCas[T](future: OperationFuture[CASValue[Object]], b: CouchbaseBucket, ec: ExecutionContext, r: Reads[T]): Future[CASValue[T]] = {
    val promise = Promise[CASValue[T]]()
    future.addListener(new OperationCompletionListener() {
      def onComplete(f: OperationFuture[_]) = {
        if (!f.getStatus.isSuccess) {
          b.driver.logger.error(f.getStatus.getMessage + " for key " + f.getKey)
          f.getStatus.getMessage match {
            case "NOT_FOUND" => promise.failure(new OperationStatusErrorNotFound(f.getStatus))
            case "LOCK_ERROR" => promise.failure(new OperationStatusErrorIsLocked(f.getStatus))
            case _ => promise.failure(new OperationStatusError(f.getStatus))
          }
        } else if (f.isDone || f.isCancelled) {
          promise.success(f.get().asInstanceOf[CASValue[T]])
        } else {
          if (b.checkFutures) promise.failure(new Throwable(s"GetFuture epic fail !!! ${f.isDone} : ${f.isCancelled}"))
          else {
            b.driver.logger.warn(s"GetFuture not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
            promise.success(f.get().asInstanceOf[CASValue[T]])
          }
        }

      }
    })
    promise.future
  }

  /**
   *
   * Transform an HttpFuture to a Future[OperationStatus]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @tparam T internal type
   * @return
   */
  def waitForHttpStatus[T](future: HttpFuture[T], b: CouchbaseBucket, ec: ExecutionContext): Future[OperationStatus] = {
    val promise = Promise[OperationStatus]()
    future.addListener(new HttpCompletionListener() {
      def onComplete(f: HttpFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled) {
            promise.success(f.getStatus)
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"HttpFutureStatus epic fail !!! ${f.isDone} : ${f.isCancelled}"))
            else {
              b.driver.logger.warn(s"HttpFutureStatus not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.getStatus)
            }
          }
        }
      }
    })
    promise.future
  }

  /**
   *
   * Transform an HttpFuture to a Future[T]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @tparam T internal type
   * @return
   */
  def waitForHttp[T](future: HttpFuture[T], b: CouchbaseBucket, ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    future.addListener(new HttpCompletionListener() {
      def onComplete(f: HttpFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled) {
            promise.success(f.get().asInstanceOf[T])
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"HttpFuture epic fail !!! ${f.isDone} : ${f.isCancelled}"))
            else {
              b.driver.logger.warn(s"HttpFuture not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.get().asInstanceOf[T])
            }
          }
        }
      }
    })
    promise.future
  }

  /**
   *
   * Transform an OperationFuture to a Future[OperationStatus]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @tparam T internal type
   * @return the Scala Future
   */
  def waitForOperationStatus[T](future: OperationFuture[T], b: CouchbaseBucket, ec: ExecutionContext): Future[OperationStatus] = {
    val promise = Promise[OperationStatus]()
    future.addListener(new OperationCompletionListener() {
      def onComplete(f: OperationFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled) {
            promise.success(f.getStatus)
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"OperationFutureStatus epic fail !!! ${f.isDone} : ${f.isCancelled}"))
            else {
              b.driver.logger.warn(s"OperationFutureStatus not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.getStatus)
            }
          }
        }
      }
    })
    promise.future
  }

  /**
   *
   * Transform an OperationFuture to a Future[T]
   *
   * @param future the Java Driver Future
   * @param b the bucket to use
   * @param ec ExecutionContext for async processing
   * @tparam T internal type
   * @return
   */
  def waitForOperation[T](future: OperationFuture[T], b: CouchbaseBucket, ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    future.addListener(new OperationCompletionListener() {
      def onComplete(f: OperationFuture[_]) = {
        if (b.failWithOpStatus && (!f.getStatus.isSuccess)) {
          promise.failure(new OperationFailedException(f.getStatus))
        } else {
          if (!f.getStatus.isSuccess) b.driver.logger.error(f.getStatus.getMessage)
          if (f.isDone || f.isCancelled) {
            promise.success(f.get().asInstanceOf[T])
          } else {
            if (b.checkFutures) promise.failure(new Throwable(s"OperationFuture epic fail !!! ${f.isDone} : ${f.isCancelled}"))
            else {
              b.driver.logger.warn(s"OperationFuture not completed yet, success anyway : ${f.isDone} : ${f.isCancelled}")
              promise.success(f.get().asInstanceOf[T])
            }
          }
        }
      }
    })
    promise.future
  }
}
