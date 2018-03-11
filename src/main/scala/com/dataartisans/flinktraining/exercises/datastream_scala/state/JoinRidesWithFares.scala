/*
 * Copyright 2017 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.flinktraining.exercises.datastream_scala.state

import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.{TaxiFare, TaxiRide}
import com.dataartisans.flinktraining.exercises.datastream_java.sources.{CheckpointedTaxiFareSource, CheckpointedTaxiRideSource}
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.co.{CoFlatMapFunction, CoProcessFunction, RichCoFlatMapFunction}
import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment, _}
import org.apache.flink.util.Collector

/**
  * Scala reference implementation for the "Join Rides with Fares" exercise of the Flink training
  * (http://training.data-artisans.com).
  *
  * The goal for this exercise is to enrich TaxiRides with fare information.
  *
  * Parameters:
  * -trips path-to-input-file
  * -fares path-to-input-file
  *
  */
object JoinRidesWithFares {
  def main(args: Array[String]) {

    // parse parameters
    val params = ParameterTool.fromArgs(args)
    val tripsFile = params.getRequired("trips")
    val faresFile = params.getRequired("fares")

    val servingSpeedFactor = 1800 // 30 minutes worth of events are served every second

    // set up streaming execution environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val rides = env
      .addSource(new CheckpointedTaxiRideSource(tripsFile, servingSpeedFactor))
      .filter { ride => !ride.isStart }
      .keyBy("rideId")

    val fares = env
      .addSource(new CheckpointedTaxiFareSource(faresFile, servingSpeedFactor))
      .keyBy("rideId")

    val processed = rides
      .connect(fares)
      .flatMap(new EnrichmentFunction)
      .print()

    env.execute("Join Rides with Fares (scala RichCoFlatMap)")
  }

  class EnrichmentFunction extends RichCoFlatMapFunction[TaxiRide, TaxiFare, (TaxiRide, TaxiFare)] {
    // keyed, managed state
    lazy val rideState: ValueState[TaxiRide] = getRuntimeContext.getState(
      new ValueStateDescriptor[TaxiRide]("saved ride", classOf[TaxiRide]))
    lazy val fareState: ValueState[TaxiFare] = getRuntimeContext.getState(
      new ValueStateDescriptor[TaxiFare]("saved fare", classOf[TaxiFare]))

    override def flatMap1(ride: TaxiRide, out: Collector[(TaxiRide, TaxiFare)]): Unit = {
      val fare = fareState.value
      if (fare != null) {
        fareState.clear()
        out.collect((ride, fare))
      }
      else {
        rideState.update(ride)
      }
    }

    override def flatMap2(fare: TaxiFare, out: Collector[(TaxiRide, TaxiFare)]): Unit = {
      val ride = rideState.value
      if (ride != null) {
        rideState.clear()
        out.collect((ride, fare))
      }
      else {
        fareState.update(fare)
      }
    }
  }

}
