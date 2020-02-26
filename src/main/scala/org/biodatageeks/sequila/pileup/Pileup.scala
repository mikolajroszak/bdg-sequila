package org.biodatageeks.sequila.pileup

import htsjdk.samtools.SAMRecord
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.biodatageeks.sequila.datasources.BAM.BDGAlignFileReaderWriter
import org.biodatageeks.sequila.datasources.InputDataType
import org.biodatageeks.sequila.inputformats.BDGAlignInputFormat
import org.biodatageeks.sequila.pileup.model.PileupRecord
import org.biodatageeks.sequila.utils.{DataQualityFuncs, InternalParams, TableFuncs}
import org.seqdoop.hadoop_bam.CRAMBDGInputFormat
import org.slf4j.LoggerFactory

import scala.reflect.ClassTag


class Pileup[T<:BDGAlignInputFormat](spark:SparkSession)(implicit c: ClassTag[T]) extends BDGAlignFileReaderWriter[T] {
  val logger = LoggerFactory.getLogger(this.getClass.getCanonicalName)

  def handlePileup(tableName: String, output: Seq[Attribute]): RDD[PileupRecord] = {
    logger.info("Calculating pileup on table: {}", tableName)

    lazy val allAlignments = readTableFile(name=tableName)
    logger.debug("Processing {} reads in total", allAlignments.count() )

    val alignments = filterAlignments(allAlignments)

    PileupMethods.calculatePileup(alignments, spark)

  }

  private def filterAlignments(alignments:RDD[SAMRecord]): RDD[SAMRecord] = {
    // any other filtering conditions should go here
    val filterFlag = spark.conf.get(InternalParams.filterReadsByFlag, "1796").toInt
    val cleaned = alignments.filter(read => read.getContig != null && (read.getFlags & filterFlag) == 0)
    logger.debug("Processing {} cleaned reads in total", cleaned.count() )
    cleaned


  }

  private def readTableFile(name: String): RDD[SAMRecord] = {
    val metadata = TableFuncs.getTableMetadata(spark, name)
    val path = metadata.location.toString

    metadata.provider match {
      case Some(f) =>
        if (f == InputDataType.BAMInputDataType)
           readBAMFile(spark.sqlContext, path, refPath = None)
        else if (f == InputDataType.CRAMInputDataType) {
          val refPath = spark.sqlContext
            .sparkContext
            .hadoopConfiguration
            .get(CRAMBDGInputFormat.REFERENCE_SOURCE_PATH_PROPERTY)
           readBAMFile(spark.sqlContext, path, Some(refPath))
        }
        else throw new Exception("Only BAM and CRAM file formats are supported in bdg_coverage.")
      case None => throw new Exception("Wrong file extension - only BAM and CRAM file formats are supported in bdg_coverage.")
    }
  }
}
