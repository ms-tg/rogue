// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.

package com.foursquare.rogue

import com.foursquare.rogue.MongoHelpers.{LegacyQueryExecutor, MongoModify, MongoSelect}
import com.mongodb.{DBObject, ReadPreference, WriteConcern}
import net.liftweb.mongodb.record.{MongoRecord, MongoMetaRecord}
import scala.collection.mutable.ListBuffer

trait LiftRogueSerializer[R] {
  def fromDBObject(dbo: DBObject): R
}

trait LiftQueryExecutor extends QueryExecutor[MongoRecord[_] with MongoMetaRecord[_]] {

  protected def serializer[M <: MongoRecord[_] with MongoMetaRecord[_], R](
      meta: M,
      select: Option[MongoSelect[R, M]]
  ): LiftRogueSerializer[R]

  override def count[M <: MongoRecord[_] with MongoMetaRecord[_], Lim <: MaybeLimited, Sk <: MaybeSkipped](
      query: AbstractQuery[M, _, _, _, Lim, Sk, _]
  )(
      implicit ev1: Lim =:= Unlimited, ev2: Sk =:= Unskipped
  ): Long = LegacyQueryExecutor.condition("count", query)(query.meta.count(_))

  override def countDistinct[
      M <: MongoRecord[_] with MongoMetaRecord[_],
      Sel <: MaybeSelected,
      Lim <: MaybeLimited,
      Sk <: MaybeSkipped,
      V
  ](
      query: AbstractQuery[M, _, _, Sel, Lim, Sk, _]
  )(
      field: M => QueryField[V, M]
  )(
      implicit
      // ev1: Sel =:= SelectedOne,
      ev2: Lim =:= Unlimited,
      ev3: Sk =:= Unskipped
  ): Long = {
    LegacyQueryExecutor.condition(
        "countDistinct",
        query
    )(
        query.meta.countDistinct(field(query.meta).field.name, _)
    )
  }

  override def fetch[M <: MongoRecord[_] with MongoMetaRecord[_], R](
      query: AbstractQuery[M, R, _, _, _, _, _],
      readPreference: ReadPreference = defaultReadPreference
  ): List[R] = {
    val s = serializer[M, R](query.meta, query.select)
    val rv = new ListBuffer[R]
    LegacyQueryExecutor.query("find", query, None)(dbo => rv += s.fromDBObject(dbo))
    rv.toList
  }

  def fetchOne[M <: MongoRecord[_] with MongoMetaRecord[_], R, Lim <: MaybeLimited](
      query: AbstractQuery[M, R, _, _, Lim, _, _],
      readPreference: ReadPreference = defaultReadPreference
  )(
      implicit ev1: Lim =:= Unlimited
  ): Option[R] = fetch(query.limit(1), readPreference).headOption

  def foreach[M <: MongoRecord[_] with MongoMetaRecord[_], R](
      query: AbstractQuery[M, R, _, _, _, _, _],
      readPreference: ReadPreference = defaultReadPreference
  )(
      f: R => Unit
  ): Unit = {
    val s = serializer[M, R](query.meta, query.select)
    LegacyQueryExecutor.query("find", query, None)(dbo => f(s.fromDBObject(dbo)))
  }

  private def drainBuffer[A, B](
      from: ListBuffer[A],
      to: ListBuffer[B],
      f: List[A] => List[B],
      size: Int
  ): Unit = {
    if (from.size >= size) {
      to ++= f(from.toList)
      from.clear
    }
  }

  def fetchBatch[M <: MongoRecord[_] with MongoMetaRecord[_], R, T](
      query: AbstractQuery[M, R, _, _, _, _, _],
      batchSize: Int,
      readPreference: ReadPreference = defaultReadPreference
  )(
      f: List[R] => List[T]
  ): Seq[T] = {
    val s = serializer[M, R](query.meta, query.select)
    val rv = new ListBuffer[T]
    val buf = new ListBuffer[R]

    LegacyQueryExecutor.query("find", query, Some(batchSize)) { dbo =>
      buf += s.fromDBObject(dbo)
      drainBuffer(buf, rv, f, batchSize)
    }
    drainBuffer(buf, rv, f, 1)

    rv.toList
  }

  def bulkDelete_!![M <: MongoRecord[_] with MongoMetaRecord[_], Sel <: MaybeSelected, Lim <: MaybeLimited, Sk <: MaybeSkipped](
      query: AbstractQuery[M, _, _, Sel, Lim, Sk, _],
      writeConcern: WriteConcern = defaultWriteConcern
  )(
      implicit
      ev1: Sel <:< Unselected,
      ev2: Lim =:= Unlimited,
      ev3: Sk =:= Unskipped
  ): Unit = {
    LegacyQueryExecutor.condition("remove", query)(/*TODO: master */query.meta.bulkDelete_!!(_))
  }

  def updateOne[M <: MongoRecord[_] with MongoMetaRecord[_]](
      query: AbstractModifyQuery[M],
      writeConcern: WriteConcern = defaultWriteConcern
  ): Unit = {
    LegacyQueryExecutor.modify(query, upsert = false, multi = false, writeConcern = Some(writeConcern))
  }

  def upsertOne[M <: MongoRecord[_] with MongoMetaRecord[_]](
      query: AbstractModifyQuery[M],
      writeConcern: WriteConcern = defaultWriteConcern
  ): Unit = {
    LegacyQueryExecutor.modify(query, upsert = true, multi = false, writeConcern = Some(writeConcern))
  }

  def updateMulti[M <: MongoRecord[_] with MongoMetaRecord[_]](
      query: AbstractModifyQuery[M],
      writeConcern: WriteConcern = defaultWriteConcern
  ): Unit = {
    LegacyQueryExecutor.modify(query, upsert = false, multi = true, writeConcern = Some(writeConcern))
  }

  def findAndUpdateOne[M <: MongoRecord[_] with MongoMetaRecord[_], R](
    query: AbstractFindAndModifyQuery[M, R],
    returnNew: Boolean = false,
    writeConcern: WriteConcern = defaultWriteConcern
  ): Option[R] = {
    val s = serializer[M, R](query.query.meta, query.query.select)
    LegacyQueryExecutor.findAndModify(query, returnNew, upsert=false, remove=false)(s.fromDBObject _)
  }

  def findAndUpsertOne[M <: MongoRecord[_] with MongoMetaRecord[_], R](
    query: AbstractFindAndModifyQuery[M, R],
    returnNew: Boolean = false,
    writeConcern: WriteConcern = defaultWriteConcern
  ): Option[R] = {
    val s = serializer[M, R](query.query.meta, query.query.select)
    LegacyQueryExecutor.findAndModify(query, returnNew, upsert=true, remove=false)(s.fromDBObject _)
  }

  def findAndDeleteOne[M <: MongoRecord[_] with MongoMetaRecord[_], R](
    query: AbstractQuery[M, R, _ <: MaybeOrdered, _ <: MaybeSelected, _ <: MaybeLimited, _ <: MaybeSkipped, _ <: MaybeHasOrClause],
    writeConcern: WriteConcern = defaultWriteConcern
  ): Option[R] = {
    val s = serializer[M, R](query.meta, query.select)
    val mod = BaseFindAndModifyQuery(query, MongoModify(Nil))
    LegacyQueryExecutor.findAndModify(mod, returnNew=false, upsert=false, remove=true)(s.fromDBObject _)
  }

  def explain[M <: MongoRecord[_] with MongoMetaRecord[_]](query: AbstractQuery[M, _, _, _, _, _, _]): String = {
    LegacyQueryExecutor.explain("find", query)
  }
}
