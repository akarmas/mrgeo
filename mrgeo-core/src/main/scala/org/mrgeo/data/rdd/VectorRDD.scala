/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.data.rdd

import org.apache.hadoop.io.LongWritable
import org.apache.spark.{TaskContext, Partition}
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.mrgeo.geometry.Geometry

object VectorRDD {
  def apply(parent: VectorRDD): VectorRDD = {
    new VectorRDD(parent)
  }
  def apply(parent: RDD[(LongWritable, Geometry)]): VectorRDD = {
    new VectorRDD(parent)
  }
}

class VectorRDD(parent: RDD[(LongWritable, Geometry)]) extends RDD[(LongWritable, Geometry)](parent) {
  @DeveloperApi
  override def compute(split: Partition, context: TaskContext): Iterator[(LongWritable, Geometry)] = {
    firstParent[(LongWritable, Geometry)].iterator(split, context)
  }

  override protected def getPartitions: Array[Partition] = {
    firstParent[(LongWritable, Geometry)].partitions
  }
}