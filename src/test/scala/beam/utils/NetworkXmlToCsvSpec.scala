package beam.utils

import beam.utils.csv.conversion.NetworkXmlToCSV
import org.scalatest.wordspec.AnyWordSpecLike

class NetworkXmlToCsvSpec extends AnyWordSpecLike {

  "networkXmlToCsv class " in {
    val path = "test/input/beamville/r5/physsim-network.xml"
    val nodeOutput = "test/input/beamville/node-network.csv"
    val linkOutput = "test/input/beamville/link-network.csv"
    val mergeOutput = "test/input/beamville/merge-network.csv"
    val delimiter = "\t"
    NetworkXmlToCSV.networkXmlParser(path, delimiter, nodeOutput, linkOutput, mergeOutput)
  }
}
