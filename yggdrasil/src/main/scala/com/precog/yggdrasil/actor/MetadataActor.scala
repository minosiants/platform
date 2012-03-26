/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package actor 

import metadata._

import com.precog.common._
import com.precog.common.util._

import blueeyes.json._
import blueeyes.json.JPath

import akka.actor.Actor
import akka.actor.ActorRef
import akka.dispatch.MessageDispatcher
import akka.dispatch.Future

import scalaz.Scalaz._

class MetadataActor(metadata: LocalMetadata) extends Actor {

  def receive = {
   
    case UpdateMetadata(inserts)              => sender ! metadata.update(inserts)
   
    case FindSelectors(path)                  => sender ! metadata.findSelectors(path)

    case FindDescriptors(path, selector)      => sender ! metadata.findDescriptors(path, selector)

    case FindPathMetadata(path, selector)     => sender ! metadata.findPathMetadata(path, selector)
    
    case FlushMetadata(serializationActor)    => sender ! (serializationActor ! metadata.currentState)
    
  }

}

class LocalMetadata(initialProjections: Map[ProjectionDescriptor, ColumnMetadata], initialClock: VectorClock) {
  
  private var projections = initialProjections

  private var messageClock = initialClock 

  def currentState() = SaveMetadata(projections, messageClock)

  def update(inserts: Seq[InsertComplete]): Unit = {
    import MetadataUpdateHelper._ 
   
    val (projUpdate, clockUpdate) = inserts.foldLeft(projections, messageClock){ 
      case ((projs, clock), insert) =>
        (projs + (insert.descriptor -> applyMetadata(insert.descriptor, insert.values, insert.metadata, projs)),
         clock.update(insert.eventId.producerId, insert.eventId.sequenceId))
    }

    projections = projUpdate
    messageClock = clockUpdate
  }

  def findSelectors(path: Path): Seq[JPath] = {
    projections.foldLeft(Vector[JPath]()) {
      case (acc, (descriptor, _)) => acc ++ descriptor.columns.collect { case ColumnDescriptor(cpath, cselector, _, _) if path == cpath => cselector }
    }
  }

  def findDescriptors(path: Path, selector: JPath): Map[ProjectionDescriptor, ColumnMetadata] = {
    @inline def isEqualOrChild(ref: JPath, test: JPath) = test.nodes startsWith ref.nodes

    @inline def matches(path: Path, selector: JPath) = (col: ColumnDescriptor) => {
      col.path == path && isEqualOrChild(selector, col.selector)
    }

    projections.filter {
      case (descriptor, _) => descriptor.columns.exists(matches(path, selector))
    }
  } 

  case class ResolvedSelector(selector: JPath, descriptor: ProjectionDescriptor, metadata: ColumnMetadata) {
    
    // probably should enforce that only one column will match in someway
    // but for now I am assuming it is true (which within this narrow context
    // it should be
    def columnType: ColumnType =
      descriptor.columns.filter( _.selector == selector )(0).valueType
  }
  
  @inline def isEqualOrChild(ref: JPath, test: JPath) = test.nodes startsWith ref.nodes

  @inline def matches(path: Path, selector: JPath) = (col: ColumnDescriptor) => {
    col.path == path && isEqualOrChild(selector, col.selector)
  }

  @inline def matching(path: Path, selector: JPath): Seq[ResolvedSelector] = 
    projections.flatMap {
      case (desc, meta) => desc.columns.collect {
        case col @ ColumnDescriptor(_,sel,_,_) if matches(path,selector)(col) => ResolvedSelector(sel, desc, meta)
      }
    }(collection.breakOut)

  def findPathMetadata(path: Path, selector: JPath): PathRoot = {
    
    @inline def isLeaf(ref: JPath, test: JPath) = {
      (test.nodes startsWith ref.nodes) && 
      test.nodes.length - 1 == ref.nodes.length
    }
    
    @inline def isObjectBranch(ref: JPath, test: JPath) = {
      (test.nodes startsWith ref.nodes) && 
      test.nodes.length > ref.nodes.length &&
      (test.nodes(ref.nodes.length) match {
        case JPathField(_) => true
        case _             => false
      })
    }
    
    @inline def isArrayBranch(ref: JPath, test: JPath) = {
      (test.nodes startsWith ref.nodes) && 
      test.nodes.length > ref.nodes.length &&
      (test.nodes(ref.nodes.length) match {
        case JPathIndex(_) => true
        case _             => false
      })
    }

    def extractIndex(base: JPath, child: JPath): Int = child.nodes(base.length) match {
      case JPathIndex(i) => i
      case _             => sys.error("assertion failed") 
    }

    def extractName(base: JPath, child: JPath): String = child.nodes(base.length) match {
      case JPathField(n) => n 
      case _             => sys.error("unpossible")
    }

    def newIsLeaf(ref: JPath, test: JPath): Boolean = ref == test 

    def selectorPartition(sel: JPath, rss: Seq[ResolvedSelector]):
        (Seq[ResolvedSelector], Seq[ResolvedSelector], Set[Int], Set[String]) = {
      val (values, nonValues) = rss.partition(rs => newIsLeaf(sel, rs.selector))
      val (indexes, fields) = rss.foldLeft( (Set.empty[Int], Set.empty[String]) ) {
        case (acc @ (is, fs), rs) => if(isArrayBranch(sel, rs.selector)) {
          (is + extractIndex(sel, rs.selector), fs)
        } else if(isObjectBranch(sel, rs.selector)) {
          (is, fs + extractName(sel, rs.selector))
        } else {
          acc
        }
      }

      (values, nonValues, indexes, fields)
    }

    def convertValues(values: Seq[ResolvedSelector]): Set[PathMetadata] = {
      values.foldLeft(Map[(JPath, ColumnType), Map[ProjectionDescriptor, ColumnMetadata]]()) {
        case (acc, rs @ ResolvedSelector(sel, desc, meta)) => 
          val key = (sel, rs.columnType)
          val update = acc.get(key).getOrElse( Map.empty[ProjectionDescriptor, ColumnMetadata] ) + (desc -> meta)
          acc + (key -> update)
      }.map {
        case ((sel, colType), meta) => PathValue(colType, meta) 
      }(collection.breakOut)
    }

    def buildTree(branch: JPath, rs: Seq[ResolvedSelector]): Set[PathMetadata] = {
      val (values, nonValues, indexes, fields) = selectorPartition(branch, rs)

      val oval = convertValues(values)
      val oidx = indexes.map { idx => PathIndex(idx, buildTree(branch \ idx, nonValues)) }
      val ofld = fields.map { fld => PathField(fld, buildTree(branch \ fld, nonValues)) }

      oval ++ oidx ++ ofld
    }

    PathRoot(buildTree(selector, matching(path, selector)))
  }



  trait PathMatch

  case class ExactPath(columnType: ColumnType)
  case class ChildPath(path: JPath)

  def toStorageMetadata(messageDispatcher: MessageDispatcher): StorageMetadata = new StorageMetadata {

    implicit val dispatcher = messageDispatcher 

    def update(inserts: Seq[InsertComplete]) = Future {
      LocalMetadata.this.update(inserts)
    }

    def findSelectors(path: Path) = Future {
      LocalMetadata.this.findSelectors(path) 
    }

    def findProjections(path: Path, selector: JPath) = Future {
      LocalMetadata.this.findDescriptors(path, selector)
    } 

    def findPathMetadata(path: Path, selector: JPath) = Future {
      LocalMetadata.this.findPathMetadata(path, selector)
    }

  }

}

object MetadataUpdateHelper {

  def applyMetadata(desc: ProjectionDescriptor, values: Seq[CValue], metadata: Seq[Set[Metadata]], projections: Map[ProjectionDescriptor, ColumnMetadata]): ColumnMetadata = {
    val initialMetadata = projections.get(desc).getOrElse(initMetadata(desc))
    val userAndValueMetadata = addValueMetadata(values, metadata.map { Metadata.toTypedMap _ })

    combineMetadata(desc, initialMetadata, userAndValueMetadata)
  }

  def addValueMetadata(values: Seq[CValue], metadata: Seq[MetadataMap]): Seq[MetadataMap] = {
    values zip metadata map { t => valueStats(t._1).map( vs => t._2 + (vs.metadataType -> vs) ).getOrElse(t._2) }
  }

  def combineMetadata(desc: ProjectionDescriptor, existingMetadata: ColumnMetadata, newMetadata: Seq[MetadataMap]): ColumnMetadata = {
    val newColumnMetadata = desc.columns zip newMetadata
    newColumnMetadata.foldLeft(existingMetadata) { 
      case (acc, (col, newColMetadata)) =>
        val updatedMetadata = acc.get(col) map { _ |+| newColMetadata } getOrElse { newColMetadata }
        acc + (col -> updatedMetadata)
    }
  }

  def initMetadata(desc: ProjectionDescriptor): ColumnMetadata = 
    desc.columns.foldLeft( Map[ColumnDescriptor, MetadataMap]() ) {
      (acc, col) => acc + (col -> Map[MetadataType, Metadata]())
    }

  def valueStats(cval: CValue): Option[Metadata] = cval.fold( 
    str = (s: String)      => Some(StringValueStats(1, s, s)),
    bool = (b: Boolean)    => Some(BooleanValueStats(1, if(b) 1 else 0)),
    int = (i: Int)         => Some(LongValueStats(1, i, i)),
    long = (l: Long)       => Some(LongValueStats(1, l, l)),
    float = (f: Float)     => Some(DoubleValueStats(1, f, f)),
    double = (d: Double)   => Some(DoubleValueStats(1, d, d)),
    num = (bd: BigDecimal) => Some(BigDecimalValueStats(1, bd, bd)),
    emptyObj = None,
    emptyArr = None,
    nul = None)
 
}   

sealed trait ShardMetadataAction

case class ExpectedEventActions(eventId: EventId, count: Int) extends ShardMetadataAction

case class FindSelectors(path: Path) extends ShardMetadataAction
case class FindDescriptors(path: Path, selector: JPath) extends ShardMetadataAction
case class FindPathMetadata(path: Path, selector: JPath) extends ShardMetadataAction

case class UpdateMetadata(inserts: Seq[InsertComplete]) extends ShardMetadataAction
case class FlushMetadata(serializationActor: ActorRef) extends ShardMetadataAction