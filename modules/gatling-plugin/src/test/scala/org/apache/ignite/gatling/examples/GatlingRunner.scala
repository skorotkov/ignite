/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.gatling.examples

import io.gatling.app.Gatling
import io.gatling.core.config.GatlingPropertiesBuilder

/**
 * Sample application running the Gatling simulation.
 */
object GatlingRunner {
  /**
   * @param args Command line arguments.
   */
  def main(args: Array[String]): Unit = {
    val simulationClass = classOf[BasicSimulation].getName

    val props = new GatlingPropertiesBuilder
    props.simulationClass(simulationClass)

    Gatling.fromMap(props.build)
  }
}
