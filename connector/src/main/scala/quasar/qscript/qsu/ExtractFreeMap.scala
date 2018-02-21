/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.qscript.qsu

import slamdata.Predef._

import quasar.{NameGenerator, RenderTreeT}
import quasar.Planner.{InternalError, PlannerErrorME}
import quasar.fp.symbolOrder
import quasar.qscript.{construction, JoinSide, LeftSide, RightSide}
import quasar.sql.JoinDir

import matryoshka.{BirecursiveT, ShowT}
import scalaz.Tags.Disjunction
import scalaz.{Monad, NonEmptyList, IList, ICons, INil, Scalaz, StateT, Tag, \/, \/-, -\/}, Scalaz._

/** Extracts `MapFunc` expressions from operations by requiring an argument
  * to be a function of one or more sibling arguments and creating an
  * autojoin if not.
  */
final class ExtractFreeMap[T[_[_]]: BirecursiveT: RenderTreeT: ShowT] private () extends QSUTTypes[T] {
  import QScriptUniform._
  import QSUGraph.Extractors

  def apply[F[_]: Monad: NameGenerator: PlannerErrorME](graph: QSUGraph)
      : F[QSUGraph] = {
    type G[A] = StateT[F, RevIdx, A]
    graph.rewriteM[G](extract[G]).eval(graph.generateRevIndex)
  }

  ////

  private type QSU[A] = QScriptUniform[A]

  private val func = construction.Func[T]

  private def extract[F[_]: Monad: NameGenerator: PlannerErrorME: RevIdxM]
      : PartialFunction[QSUGraph, F[QSUGraph]] = {

    case graph @ Extractors.GroupBy(src, key) =>
      unifyShapePreserving[F](graph, src.root, NonEmptyList(key.root))("group_source", "group_key") {
        case (sym, fms) => DimEdit(sym, DTrans.Group(fms.head))
      }

    case graph @ Extractors.LPFilter(src, predicate) =>
      unifyShapePreserving[F](graph, src.root, NonEmptyList(predicate.root))("filter_source", "filter_predicate") {
        case (sym, fms) => QSFilter(sym, fms.head)
      }

    case graph @ Extractors.LPJoin(left, right, cond, jtype, lref, rref) => {
      val combiner: JoinFunc =
        func.StaticMapS(
          JoinDir.Left.name -> func.LeftSide,
          JoinDir.Right.name -> func.RightSide)

      def refReplace(g: QSUGraph, l: Symbol, r: Symbol): Symbol => JoinSide \/ QSUGraph =
        replaceRefs(g, l, r) >>> (_.toLeft(g).disjunction)

      def mappableOf(g: QSUGraph, l: Symbol, r: Symbol): Boolean =
        replaceRefs(g, l, r)(g.root).isDefined

      MappableRegion.funcOf(replaceRefs(graph, lref, rref), graph refocus cond.root).cata(jf =>
        graph.overwriteAtRoot(ThetaJoin(left.root, right.root, jf, jtype, combiner)).point[F], {
          val max = MappableRegion.maximal(graph refocus cond.root)
          val joinFunc = max.map(qg => refReplace(qg, lref, rref)(qg.root))
          val err = InternalError(s"Unable to unify targets", None)

          val targets: (IList[QSUGraph], IList[QSUGraph]) =
            max.toIList.partition(hasJoinRef(_, lref))
              .bimap(
                _.filterNot(mappableOf(_, lref, rref)), _.filterNot(mappableOf(_, lref, rref)))

          targets match {
            case (ICons(leftTarget, INil()), ICons(rightTarget, INil())) => {
              val leftUnify =
                UnifyTargets[T, F](withName[F](_))(graph, left.root, NonEmptyList(leftTarget.root))("left_source", "left_target")
              val rightUnify =
                UnifyTargets[T, F](withName[F](_))(graph, right.root, NonEmptyList(rightTarget.root))("right_source", "right_target")

              (leftUnify |@| rightUnify) {
                case ((leftGraph, leftOrig, leftSide), (rightGraph, rightOrig, rightSide)) => {
                  val leftSym = leftTarget.root
                  val rightSym = rightTarget.root

                  val on: Option[JoinFunc] = max.traverseM(qg => qg.root match {
                    case `leftSym` => leftSide.head.as[JoinSide](LeftSide).some
                    case `rightSym` => rightSide.head.as[JoinSide](RightSide).some
                    case _ => none
                  })

                  val repair: JoinFunc = combiner.map({
                    case LeftSide => leftOrig.as[JoinSide](LeftSide)
                    case RightSide => rightOrig.as[JoinSide](RightSide)
                  }).join

                  on.cata(on0 => {
                    val node = ThetaJoin(leftGraph.root, rightGraph.root, on0, jtype, repair)
                    val newGraph = leftGraph :++ rightGraph

                    (graph.overwriteAtRoot(node) :++ leftGraph :++ rightGraph).point[F]
                  }, PlannerErrorME[F].raiseError[QSUGraph](err))
                }
              }.join
            }
            case (ICons(leftTarget, INil()), INil()) => {
              val leftUnify =
                UnifyTargets[T, F](withName[F](_))(graph, left.root, NonEmptyList(leftTarget.root))("left_source", "left_target")

              leftUnify >>= { case (leftGraph, leftOrig, leftSide) =>
                val leftSym = leftTarget.root
                val on: Option[JoinFunc] = joinFunc.traverseM({
                  case -\/(side) => side.point[FreeMapA].some
                  case \/-(g) => g.root match {
                    case `leftSym` => leftSide.head.as[JoinSide](LeftSide).some
                    case _ => none
                  }
                })

                val repair: JoinFunc = combiner.map({
                  case LeftSide => leftOrig.as[JoinSide](LeftSide)
                  case RightSide => (RightSide: JoinSide).point[FreeMapA]
                }).join

                on.cata(on0 => {
                  val node = ThetaJoin(leftGraph.root, right.root, on0, jtype, repair)

                  (graph.overwriteAtRoot(node) :++ leftGraph).point[F]
                }, PlannerErrorME[F].raiseError[QSUGraph](err))
              }
            }
            case (INil(), ICons(rightTarget, INil())) => {
              val rightUnify =
                UnifyTargets[T, F](withName[F](_))(graph, right.root, NonEmptyList(rightTarget.root))("right_source", "right_target")

              rightUnify >>= { case (rightGraph, rightOrig, rightSide) =>
                val rightSym = rightTarget.root
                val on: Option[JoinFunc] = joinFunc.traverseM({
                  case -\/(side) => side.point[FreeMapA].some
                  case \/-(g) => g.root match {
                    case `rightSym` => rightSide.head.as[JoinSide](LeftSide).some
                    case _ => none
                  }
                })

                val repair: JoinFunc = combiner.map({
                  case LeftSide => (LeftSide: JoinSide).point[FreeMapA]
                  case RightSide => rightOrig.as[JoinSide](RightSide)
                }).join

                on.cata(on0 => {
                  val node = ThetaJoin(left.root, rightGraph.root, on0, jtype, repair)

                  (graph.overwriteAtRoot(node) :++ rightGraph).point[F]
                }, PlannerErrorME[F].raiseError[QSUGraph](err))
              }
            }
            case _ =>
              PlannerErrorME[F].raiseError[QSUGraph](
                InternalError(s"Invalid join condition, $cond, must be a mappable function of $left and $right.", None))
          }
        })
    }

    case graph @ Extractors.LPSort(src, keys) =>
      unifyShapePreserving[F](graph, src.root, keys map (_._1.root))("sort_source", "sort_key") {
        case (sym, fms) => QSSort(sym, Nil, fms fzip keys.seconds)
      }
  }

  private def hasJoinRef(g: QSUGraph, refId: Symbol): Boolean =
    Tag.unwrap[Boolean, Disjunction](g.foldMapUp(g => g.unfold.map(_.root) match {
      case JoinSideRef(rid) if refId === rid =>
        Tag[Boolean, Disjunction](true)
      case _ =>
        Tag[Boolean, Disjunction](false)
    }))

  private def replaceRefs(g: QSUGraph, l: Symbol, r: Symbol)
      : Symbol => Option[JoinSide] =
    s => g.vertices.get(s) collect {
      case JoinSideRef(`l`) => LeftSide
      case JoinSideRef(`r`) => RightSide
    }

  private def unifyShapePreserving[F[_]: Monad: NameGenerator: RevIdxM](
      graph: QSUGraph,
      source: Symbol,
      targets: NonEmptyList[Symbol])(
      sourceName: String,
      targetPrefix: String)(
      buildNode: (Symbol, NonEmptyList[FreeMap]) => QScriptUniform[Symbol]): F[QSUGraph] =
    UnifyTargets[T, F](withName[F](_))(graph, source, targets)(sourceName, targetPrefix) flatMap {
      case (newSrc, original, targetExprs) =>
        val node = buildNode(newSrc.root, targetExprs)

        if (newSrc.root === source)
          graph.overwriteAtRoot(node).point[F]
        else
          withName[F](node) map { inter =>
            graph.overwriteAtRoot(Map(inter.root, original)) :++ inter :++ newSrc
          }
    }

  private def withName[F[_]: Monad: NameGenerator: RevIdxM](node: QScriptUniform[Symbol]): F[QSUGraph] =
    QSUGraph.withName[T, F]("efm")(node)
}

object ExtractFreeMap {
  def apply[
      T[_[_]]: BirecursiveT: RenderTreeT: ShowT,
      F[_]: Monad: NameGenerator: PlannerErrorME]
      (graph: QSUGraph[T])
      : F[QSUGraph[T]] =
    taggedInternalError("ExtractFreeMap", new ExtractFreeMap[T].apply[F](graph))
}
