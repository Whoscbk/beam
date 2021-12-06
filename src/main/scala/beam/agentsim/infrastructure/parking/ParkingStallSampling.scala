package beam.agentsim.infrastructure.parking

import scala.util.Random
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.Location
import org.matsim.api.core.v01.Coord

/**
  * sampling methods for randomly generating stall locations from aggregate information
  */
object ParkingStallSampling {

  type GeoSampling[GEO] = (Random, Location, GEO, Double, Boolean) => Location
  val maxOffsetDistance = 600.0 // TODO: Make this a config parameter

  /**
    * generates stall locations per a sampling technique which induces noise as a function of stall attribute availability
    * @param rand random generator used to create stall locations
    * @param agent position of agent
    * @param taz position of TAZ centroid
    * @param availabilityRatio availability of the chosen stall type, as a ratio, i.e., in the range [0, 1]
    * @return a sampled location
    */
  def availabilityAwareSampling(
    rand: Random,
    agent: Location,
    taz: TAZ,
    availabilityRatio: Double,
    closestZone: Boolean = true
  ): Location = {

    val xDistance: Double = taz.coord.getX - agent.getX
    val yDistance: Double = taz.coord.getY - agent.getY
    val euclideanDistanceToTazCenter = Math.sqrt(Math.pow(xDistance, 2.0) + Math.pow(yDistance, 2.0))
    val tazCharacteristicDiameter: Double = math.sqrt(taz.areaInSquareMeters)
    val sampleStandardDeviation: Double = if (closestZone) { 200.0 }
    else { tazCharacteristicDiameter * 0.33 }

    // this coefficient models the effect of parking supply constraint on the distance a parking stall
    // might be placed from the agent's desired destination
    val availabilityFactor: Double =
      if (availabilityRatio < Math.exp(-4.0)) 1.0 else -0.25 * math.log(availabilityRatio)

    // finding a location between the agent and the TAZ centroid to sample from, scaled back by increased availability
    val (expectedValueX, expectedValueY) = if (closestZone) {
      (
        xDistance * availabilityFactor + agent.getX,
        yDistance * availabilityFactor + agent.getY
      )
    } else {
      (
        taz.coord.getX - xDistance * (tazCharacteristicDiameter / euclideanDistanceToTazCenter),
        taz.coord.getY - xDistance * (tazCharacteristicDiameter / euclideanDistanceToTazCenter)
      )
    }

    val (offsetX, offsetY) = (
      rand.nextGaussian * availabilityFactor * sampleStandardDeviation,
      rand.nextGaussian * availabilityFactor * sampleStandardDeviation
    )

    val offsetDistance = Math.sqrt(Math.pow(offsetX, 2.0) + Math.pow(offsetY, 2.0))

    val offsetMultiplier = if (offsetDistance <= maxOffsetDistance) {1.0} else {maxOffsetDistance / offsetDistance}

    // the random variable has a standard deviation made of an inverse of parking availability and scaled out
    // proportionally to 1/3 the diameter of the TAZ.
    // random value offset by the agent location and additionally offset by the distance from agent
    // to TAZ centroid with inverse availability also being a factor here.
    val (sampleX, sampleY) = (
      offsetX * offsetMultiplier + expectedValueX,
      offsetY * offsetMultiplier + expectedValueY
    )

    new Coord(sampleX, sampleY)
  }

  /**
    * samples a random location near a TAZ's centroid in order to create a stall in that TAZ.
    * previous dev's note: make these distributions more custom to the TAZ and stall type
    * @param rand random generator
    * @param center location we are sampling from
    *
    * @return a coordinate near that TAZ
    *
    * @deprecated
    */
  def sampleLocationForStall(rand: Random, center: Location, radius: Double): Location = {
    val lambda = 0.01
    val deltaRadiusX = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda
    val deltaRadiusY = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = center.getX + deltaRadiusX
    val y = center.getY + deltaRadiusY
    new Location(x, y)
  }
}
