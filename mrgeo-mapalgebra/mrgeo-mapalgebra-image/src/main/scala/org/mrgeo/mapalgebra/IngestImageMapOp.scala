/*
 * Copyright 2009-2016 DigitalGlobe, Inc.
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
 *
 */

package org.mrgeo.mapalgebra

import java.io.{Externalizable, IOException, ObjectInput, ObjectOutput}

import org.apache.hadoop.fs.Path
import org.apache.spark.{SparkConf, SparkContext}
import org.mrgeo.core.{MrGeoConstants, MrGeoProperties}
import org.mrgeo.data.rdd.RasterRDD
import org.mrgeo.hdfs.utils.HadoopFileUtils
import org.mrgeo.image.MrsPyramidMetadata
import org.mrgeo.ingest.IngestImage
import org.mrgeo.job.JobArguments
import org.mrgeo.mapalgebra.parser.{ParserException, ParserNode}
import org.mrgeo.mapalgebra.raster.RasterMapOp
import org.mrgeo.utils.GDALUtils

import scala.util.control.Breaks

object IngestImageMapOp extends MapOpRegistrar {

  override def register: Array[String] = {
    Array[String]("ingest")
  }

  def create(input:String):MapOp =
    new IngestImageMapOp(input, None, None)

  def create(input:String, zoom:Int):MapOp =
    new IngestImageMapOp(input, Some(zoom), None)

  def create(input:String, categorical:Boolean):MapOp =
    new IngestImageMapOp(input, None, Some(categorical))

  def create(input:String, zoom:Int, categorical:Boolean):MapOp =
    new IngestImageMapOp(input, Some(zoom), Some(categorical))


  override def apply(node:ParserNode, variables: String => Option[ParserNode]): MapOp =
    new IngestImageMapOp(node, variables)
}

class IngestImageMapOp extends RasterMapOp with Externalizable {

  private var rasterRDD: Option[RasterRDD] = None

  private var input:Option[String] = None
  private var categorical:Option[Boolean] = None
  private var zoom:Option[Int] = None

  private[mapalgebra] def this(input:String, zoom:Option[Int], categorical:Option[Boolean]) = {
    this()
    this.input = Some(input)
    this.categorical = categorical
    this.zoom = zoom
  }

  private[mapalgebra] def this(node: ParserNode, variables: String => Option[ParserNode]) = {
    this()

    if (node.getNumChildren < 1 || node.getNumChildren > 3) {
      throw new ParserException("Usage: ingest(input(s), [zoom], [categorical]")
    }

    input = MapOp.decodeString(node.getChild(0), variables)

    if (node.getNumChildren >= 2) {
      zoom = MapOp.decodeInt(node.getChild(1), variables)
    }

    if (node.getNumChildren >= 3) {
      categorical = MapOp.decodeBoolean(node.getChild(2), variables)
    }
  }

  override def registerClasses(): Array[Class[_]] = {
    // IngestImage ultimately creates a WrappedArray of Array[String], WrappedArray is already
    // registered, so we need the Array[String]
    Array[Class[_]](classOf[Array[String]])
  }


  override def rdd(): Option[RasterRDD] = rasterRDD

  override def setup(job: JobArguments, conf: SparkConf): Boolean = {

    true
  }

  override def execute(context: SparkContext): Boolean = {

    val inputs = input.getOrElse(throw new IOException("Inputs not set"))

    val path = new Path(inputs)
    val fs = HadoopFileUtils.getFileSystem(context.hadoopConfiguration, path)

    val rawfiles = fs.listFiles(path, true)

    val filebuilder = Array.newBuilder[String]
    while (rawfiles.hasNext) {
      val raw = rawfiles.next()

      if (!raw.isDirectory) {
        filebuilder += raw.getPath.toUri.toString
      }
    }
    val tilesize = MrGeoProperties.getInstance().getProperty(MrGeoConstants.MRGEO_MRS_TILESIZE, MrGeoConstants.MRGEO_MRS_TILESIZE_DEFAULT).toInt

    if (zoom.isEmpty) {
      var newZoom = 1
      filebuilder.result().foreach(file => {
        val z = GDALUtils.calculateZoom(file, tilesize)
        if (z > newZoom) {
          newZoom = z
        }
      })
      zoom = Some(newZoom)
    }

    var nodatas:Array[Number] = null

    val done = new Breaks
    done.breakable({
      filebuilder.result().foreach(file => {
        try {
          nodatas = GDALUtils.getnodatas(file)
          done.break()
        }
        catch {
          case e:Exception => // ignore and go on
        }
      })
    })

    if (categorical.isEmpty) {
      categorical = Some(false)
    }

    val result = IngestImage.ingest(context, filebuilder.result(), zoom.get, tilesize, categorical.get, nodatas)
    rasterRDD = result._1 match {
    case rrdd:RasterRDD =>
      rrdd.checkpoint()
      Some(rrdd)
    case _ => None
    }

    metadata(result._2 match {
    case md:MrsPyramidMetadata => md
    case _ => null
    })

    true
  }

  override def teardown(job: JobArguments, conf: SparkConf): Boolean = true

  override def readExternal(in: ObjectInput): Unit = {}
  override def writeExternal(out: ObjectOutput): Unit = {}

}
