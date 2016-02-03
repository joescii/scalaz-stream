package fs2.internal

import Resources._

/**
 * Some implementation notes:
 *
 * `Some(r)` in the `LinkedMap` represents an acquired resource;
 * `None` represents a resource in the process of being acquired
 * The `Status` indicates whether this resource is 'open' or not.
 * Once `Closed` or `Closing`, all `startAcquire` calls will return `false`.
 * When `Closing`, all calls to `finishAcquire` or `cancelAcquire` will
 * transition to `Closed` if there are no more outstanding acquisitions.
 *
 * Once `Closed` or `Closing`, there is no way to reopen a `Resources`.
 */
private[fs2] class Resources[T,R](tokens: Ref[(Status, LinkedMap[T, Option[R]])]) {

  def isOpen: Boolean = tokens.get._1 == Open
  def isClosed: Boolean = tokens.get._1 == Closed
  def isEmpty: Boolean = tokens.get._2.isEmpty

  /**
   * Close this `Resources` and return all acquired resources.
   * The `Boolean` is `false` if there are any outstanding
   * resources in the `Acquiring` state. After finishing,
   * no calls to `startAcquire` will succeed.
   */
  @annotation.tailrec
  final def closeAll: Option[List[R]] = tokens.access match {
    case ((open,m),update) =>
      val totallyDone = m.values.forall(_ != None)
      def rs = m.values.collect { case Some(r) => r }.toList
      def m2 = if (!totallyDone) m else m.unorderedEntries.foldLeft(m) { (m,kv) =>
        kv._2 match {
          case None => m
          case Some(_) => m - (kv._1: T)
        }
      }
      if (!update((if (totallyDone) Closed else Closing, m2))) closeAll
      else if (totallyDone) Some(rs)
      else None
  }

  /**
   * Close `t`, returning any associated acquired resource.
   * Returns `None` if `t` is being acquired or `t` is
   * not present in this `Resources`.
   */
  @annotation.tailrec
  final def close(t: T): Option[R] = tokens.access match {
    case ((open,m),update) => m.get(t) match {
      case None => None // note: not flatMap so can be tailrec
      case Some(Some(r)) => // close of an acquired resource
        if (update((open, m-t))) Some(r)
        else close(t)
      case Some(None) => None // close of any acquiring resource fails
    }
  }

  /**
   * Start acquiring `t`.
   * Returns `None` if `t` is being acquired or `t` is
   * not present in this `Resources`.
   */
  @annotation.tailrec
  final def startAcquire(t: T): Boolean = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(r) => sys.error("startAcquire on already used token: "+(t -> r))
        case None => open == Open && {
          update(open -> m.edit(t, _ => Some(None))) || startAcquire(t)
        }
      }
  }

  /**
   * Cancel acquisition of `t`.
   */
  @annotation.tailrec
  final def cancelAcquire(t: T): Unit = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(Some(r)) => () // sys.error("token already acquired: "+ (t -> r))
        case None => ()
        case Some(None) =>
          val m2 = m - t
          val totallyDone = m2.values.forall(_ != None)
          val status = if (totallyDone && open == Closing) Closed else open
          if (!update(status -> m2)) cancelAcquire(t)
      }
  }

  /**
   * Associate `r` with the given `t`.
   * Returns `open` status of this `Resources` as of the update.
   */
  @annotation.tailrec
  final def finishAcquire(t: T, r: R): Unit = tokens.access match {
    case ((open,m), update) =>
      m.get(t) match {
        case Some(None) =>
          val m2 = m.edit(t, _ => Some(Some(r)))
          if (!update(open -> m2)) finishAcquire(t,r) // retry on contention
        case r => sys.error("expected acquiring status, got: " + r)
      }
  }

  override def toString = tokens.toString
}

private[fs2] object Resources {

  def empty[T,R]: Resources[T,R] =
    new Resources[T,R](Ref(Open -> LinkedMap.empty))

  trait Status
  case object Closed extends Status
  case object Closing extends Status
  case object Open extends Status
}