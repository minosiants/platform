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

import akka.actor._
import akka.dispatch._
import akka.util._
import akka.util.duration._
import akka.pattern.ask
import akka.pattern.gracefulStop

import com.precog.common.util._
import com.precog.common.kafka._

import com.weiglewilczek.slf4s.Logging

import java.net.InetAddress

import blueeyes.json.JsonAST._

trait StandaloneActorEcosystem extends BaseActorEcosystem with YggConfigComponent with Logging {
  protected lazy val pre = "[Standalone Yggdrasil Shard]"

  lazy val actorSystem = ActorSystem("standalone_actor_system")

  lazy val routingActor = actorSystem.actorOf(Props(new BatchStoreActor(routingDispatch, yggConfig.batchStoreDelay, None, actorSystem.scheduler, yggConfig.batchShutdownCheckInterval)), "router")
  
  def actorsStatus(): Future[JArray] = Future {
    JArray(List(JString("StandaloneActorEcosystem status not yet implemented.")))
  }

  protected def actorsStopInternal: Future[Unit] = {
    for {
      _  <- actorStop(projectionActors, "projection")
      _  <- actorStop(metadataActor, "metadata")
      _  <- actorStop(metadataSerializationActor, "flush")
    } yield ()
  }
  
  //
  // Internal only actors
  //
  
  protected lazy val checkpoints: YggCheckpoints = new YggCheckpoints {
    def saveRecoveryPoint(checkpoints: YggCheckpoint) { }
  }
}
// vim: set ts=4 sw=4 et: