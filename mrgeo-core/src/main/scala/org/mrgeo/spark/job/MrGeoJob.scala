package org.mrgeo.spark.job

import org.apache.spark._
import org.mrgeo.core.{MrGeoConstants, MrGeoProperties}

import scala.collection.JavaConversions._

abstract class MrGeoJob extends Logging {
  def registerClasses(): Array[Class[_]]

  def setup(job: JobArguments, conf:SparkConf): Boolean

  def execute(context: SparkContext): Boolean

  def teardown(job: JobArguments, conf:SparkConf): Boolean

  private[job] def run(job:JobArguments, conf:SparkConf) = {
    // need to do this here, so we can call registerClasses() on the job.
    PrepareJob.setupSerializer(this, job, conf)

    logInfo("Setting up job")
    setup(job, conf)

    val context = new SparkContext(conf)
    try {
      logInfo("Running job")
      execute(context)
    }
    finally {
      logInfo("Stopping spark context")
      context.stop()
    }

    teardown(job, conf)
  }



  //  final def main(args:Array[String]): Unit = {
  //    val job:JobArguments = new JobArguments(args)
  //
  //    println("Starting application")
  //
  //    val conf:SparkConf = new SparkConf()
  //        .setAppName(System.getProperty("spark.app.name", "generic-spark-app"))
  //        .setMaster(System.getProperty("spark.master", "local[1]"))
  //    //        .setJars(System.getProperty("spark.jars", "").split(","))
  //L::
  //    val context:SparkContext = new SparkContext(conf)
  //    try
  //    {
  //      execute(context)
  //    }
  //    finally
  //    {
  //      println("Stopping spark context")
  //      context.stop()
  //    }
  //  }

}
