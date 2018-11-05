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

package quasar.regression

import slamdata.Predef.{Map => SMap, _}
import quasar._
import quasar.api.QueryEvaluator
import quasar.common.{PhaseResults, PhaseResultTell}
import quasar.contrib.scalaz.{MonadError_, MonadTell_}
import quasar.qscript._
import quasar.run.{MonadQuasarErr, QuasarError, Sql2QueryEvaluator, SqlQuery}
import quasar.sql.Query

import cats.effect.IO
import org.specs2.matcher.{Expectable, MatchResult, Matcher}
import matryoshka.data._
import pathy.Path
import shims._

object QScriptRegressionSpec extends Qspec {

  implicit val ioQuasarError: MonadQuasarErr[IO] =
    MonadError_.facet[IO](QuasarError.throwableP)

  implicit val ioPlannerError: MonadPlannerErr[IO] =
    MonadError_.facet[IO](QuasarError.planning)

  implicit val ioMonadTell: PhaseResultTell[IO] =
    MonadTell_.ignore[IO, PhaseResults]

  val sql2Evaluator: QueryEvaluator[IO, SqlQuery, QScriptCount] =
    Sql2QueryEvaluator[Fix, IO, QScriptCount](
      new RegressionQueryEvaluator[Fix, IO])

  def query(q: String): SqlQuery =
    SqlQuery(Query(q), Variables(SMap()), Path.rootDir)

  def count(sql2: String): QScriptCount =
    sql2Evaluator.evaluate(query(sql2)).unsafeRunSync()

  ////

  "pushdown-optimized qscript" should {

    "have a ShiftedRead" >> {

      val q1 = "select * from foo"
      q1 in {
        val result = count(q1)

        result must countShiftedReadAs(1)
        result must countInterpretedReadAs(0)
        result must countLeftShiftAs(0)
      }
    }

    "have an InterpretedRead" >> {

      val q1 = "select *{_} from foo"
      q1 in {
        val result = count(q1)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }

      val q2 = "select *[_] from foo"
      q2 in {
        val result = count(q2)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }

      val q3 = "select *{_}, *{_:} from foo"
      q3 in {
        val result = count(q3)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }

      val q4 = "select *[_], *[_:] from foo"
      q4 in {
        val result = count(q4)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }

      val q5 = "select a[7].c{_}.x, a[7].c{_:} from foo"
      q5 in {
        val result = count(q5)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }

      val q6 = "select a[7].c[_].x, a[7].c[_:] from foo"
      q6 in {
        val result = count(q6)

        result must countShiftedReadAs(0)
        result must countInterpretedReadAs(1)
        result must countLeftShiftAs(0)
      }
    }

    "have multiple LeftShift" >> {
      val q1 = "select a{_}, b[_], c{_:} from foo"
      q1 in {
        val result = count(q1)

        result must countShiftedReadAs(1)
        result must countInterpretedReadAs(0)
        result must countLeftShiftAs(3)
      }

      val q2 = "(select a{_} from foo) union all (select b[_] from bar)"
      q2 in {
        val result = count(q2)

        result must countShiftedReadAs(2)
        result must countInterpretedReadAs(0)
        result must countLeftShiftAs(2)
      }
    }
  }

  def countInterpretedReadAs(expected: Int): Matcher[QScriptCount] =
    new Matcher[QScriptCount] {
      def apply[S <: QScriptCount](s: Expectable[S]): MatchResult[S] = {
        val actual = s.value.interpretedRead.count
        result(
          actual == expected,
          s"Received expected ${s.value}",
          s"Received unexpected ${s.value}",
          s,
          expected.toString,
          actual.toString)
      }
    }

  def countShiftedReadAs(expected: Int): Matcher[QScriptCount] =
    new Matcher[QScriptCount] {
      def apply[S <: QScriptCount](s: Expectable[S]): MatchResult[S] = {
        val actual = s.value.shiftedRead.count
        result(
          actual == expected,
          s"Received expected ${s.value}",
          s"Received unexpected ${s.value}",
          s,
          expected.toString,
          actual.toString)
      }
    }

  def countLeftShiftAs(expected: Int): Matcher[QScriptCount] =
    new Matcher[QScriptCount] {
      def apply[S <: QScriptCount](s: Expectable[S]): MatchResult[S] = {
        val actual = s.value.leftShift.count
        result(
          actual == expected,
          s"Received expected ${s.value}",
          s"Received unexpected ${s.value}",
          s,
          expected.toString,
          actual.toString)
      }
    }
}
