package beam.calibration.impl.example

import java.net.URI
import java.nio.file.Paths

import scala.io.Source
import scala.util.Try

import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.apache.http.client.fluent.{Content, Request}

import beam.analysis.plots.{GraphsStatsAgentSimEventsListener, ModeChosenStats}
import beam.calibration.api.{FileBasedObjectiveFunction, ObjectiveFunction}
import beam.calibration.impl.example.ModeChoiceObjectiveFunction.ModeChoiceStats
import beam.utils.FileUtils.using

class ModeChoiceObjectiveFunction(benchmarkDataFileLoc: String)
    extends ObjectiveFunction {

  implicit val modeChoiceDataDecoder: Decoder[ModeChoiceStats] = deriveDecoder[ModeChoiceStats]

  override def evaluateFromRun(runDataFileLoc: String): Double = {

    val benchmarkData = if (benchmarkDataFileLoc.contains("http://")) {
      getStatsFromMTC(new URI(runDataFileLoc))
    } else {
      getStatsFromFile(benchmarkDataFileLoc)
    }

    compareStats(benchmarkData, getStatsFromFile(runDataFileLoc))
  }

  /**
    * Computes MPE between run data and benchmark data on a mode-to-mode basis.
    *
    * @param benchmarkData target values of mode shares
    * @param runData       output values of mode shares given current suggestion.
    * @return the '''negative''' RMSPE value (since we '''maximize''' the objective).
    */
  def compareStats(
    benchmarkData: Map[String, Double],
    runData: Map[String, Double]
  ): Double = {
    val res = -Math.sqrt(
      runData
        .map({
          case (k, y_hat) =>
            val y = benchmarkData(k)
            Math.pow((y - y_hat) / y, 2)
        })
        .sum / runData.size
    )
    res
  }

  def getStatsFromFile(fileLoc: String): Map[String, Double] = {
    val lines = Source.fromFile(fileLoc).getLines().toArray
    val header = lines.head.split(",").tail
    val lastIter = lines.reverse.head.split(",").tail.map(_.toDouble)
    val total = lastIter.sum
    val pcts = lastIter.map(x => x / total)
    header.zip(pcts).toMap
  }

  def getStatsFromMTC(mtcBenchmarkEndPoint: URI): Map[String, Double] = {
    (for {
      mtcContent         <- getMTCContent(mtcBenchmarkEndPoint)
      parsedData         <- parseMTCRequest(mtcContent)
      modeChoiceStatList <- jsonToModechoiceStats(parsedData)
    } yield {
      modeChoiceStatList.map { stat =>
        stat.mode -> stat.share
      }.toMap
    }).get
  }

  def getMTCContent(benchmarkDataFileLoc: URI): Try[String] = {
    Try {
      val content: Content = Request.Get(benchmarkDataFileLoc).execute.returnContent
      content.asString()
    }
  }

  def parseMTCRequest(rawResponseData: String): Try[Json] = {
    parse(rawResponseData).toTry
  }

  def jsonToModechoiceStats(modechoiceJson: Json): Try[List[ModeChoiceStats]] = {
    Try {
      modechoiceJson.as[List[ModeChoiceStats]].right.get
    }
  }

}

object ModeChoiceObjectiveFunction {

  case class ModeChoiceStats(
    year: String,
    source: String,
    region: String,
    share: Double,
    mode: String,
    data_type: String
  )

}
