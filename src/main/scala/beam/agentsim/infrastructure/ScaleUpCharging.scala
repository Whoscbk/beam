package beam.agentsim.infrastructure

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles._
import beam.agentsim.events.RefuelSessionEvent.NotApplicable
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ChargingNetworkManager._
import beam.agentsim.infrastructure.ParkingInquiry.ParkingActivityType
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.scheduler.BeamAgentScheduler.{CompletionNotice, ScheduleTrigger}
import beam.agentsim.scheduler.Trigger
import beam.agentsim.scheduler.Trigger.TriggerWithId
import beam.sim.config.BeamConfig.Beam.Agentsim
import beam.utils.{MathUtils, VehicleIdGenerator}
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps
import scala.util.Random
import scala.collection.mutable

trait ScaleUpCharging extends {
  this: ChargingNetworkManager =>
  import ScaleUpCharging._

  private lazy val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
  private lazy val mersenne: MersenneTwister = new MersenneTwister(beamConfig.matsim.modules.global.randomSeed)
  private lazy val cnmConfig: Agentsim.ChargingNetworkManager = beamConfig.beam.agentsim.chargingNetworkManager
  private lazy val timeStepByHour = beamConfig.beam.agentsim.chargingNetworkManager.timeStepInSeconds / 3600.0

  private lazy val scaleUpFactor: Double =
    if (cnmConfig.scaleUp.enabled) Math.max(cnmConfig.scaleUp.expansionFactor - 1.0, 0.0)
    else 0.0

  protected lazy val inquiryMap: TrieMap[Int, ParkingInquiry] = TrieMap()
  protected lazy val simulatedEvents: mutable.Map[Id[TAZ], mutable.Map[ChargingPointType, ChargingData]] = mutable.Map()

  override def loggedReceive: Receive = {
    case t @ TriggerWithId(PlanParkingInquiryTrigger(_, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      sender ! CompletionNotice(triggerId)
      self ! inquiryMap(requestId)
    case t @ TriggerWithId(PlanChargingUnplugRequestTrigger(tick, beamVehicle, requestId), triggerId) =>
      log.debug(s"Received parking response: $t")
      sender ! CompletionNotice(triggerId)
      self ! ChargingUnplugRequest(tick, beamVehicle, triggerId)
      inquiryMap.remove(requestId)
    case response @ ParkingInquiryResponse(stall, requestId, triggerId) =>
      log.debug(s"Received parking response: $response")
      inquiryMap.get(requestId) match {
        case Some(parkingInquiry) if stall.chargingPointType.isDefined =>
          val beamVehicle = parkingInquiry.beamVehicle.get
          self ! ChargingPlugRequest(
            parkingInquiry.destinationUtm.time,
            beamVehicle,
            stall,
            parkingInquiry.personId.map(Id.create(_, classOf[Person])).getOrElse(Id.create("", classOf[Person])),
            triggerId,
            NotApplicable,
            None
          )
          val endTime = (parkingInquiry.destinationUtm.time + parkingInquiry.parkingDuration).toInt
          getScheduler ! ScheduleTrigger(
            PlanChargingUnplugRequestTrigger(endTime, beamVehicle, parkingInquiry.requestId),
            self
          )
        case Some(_) if stall.chargingPointType.isEmpty =>
          log.debug(s"parking inquiry with requestId $requestId returned a NoCharger stall")
        case _ =>
          log.warning(s"inquiryMap does not have this requestId $requestId that returned stall $stall")
      }
    case reply @ StartingRefuelSession(_, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ EndingRefuelSession(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ WaitingToCharge(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnhandledVehicle(_, _, _) =>
      log.debug(s"Received parking response: $reply")
    case reply @ UnpluggingVehicle(_, _, _) =>
      log.debug(s"Received parking response: $reply")
  }

  /**
    * Next Time poisson
    * @param rate rate of charging event
    * @return
    */
  private def nextTimePoisson(rate: Double): Double = 3600.0 * (-Math.log(1.0 - rand.nextDouble()) / rate)

  /**
    * @param chargingDataSummaryMap
    * @param timeBin
    * @param triggerId
    * @return
    */
  protected def simulateEvents(
    chargingDataSummaryMap: Map[Id[TAZ], ChargingPointData],
    timeBin: Int,
    triggerId: Long
  ): Vector[ScheduleTrigger] = {
    import MathUtils._
    chargingDataSummaryMap.par.flatMap { case (tazId, chargingPointData) =>
      val distribution = new EnumeratedDistribution[ChargingPointType](
        mersenne,
        chargingPointData.chargingPointMap
          .map { case (chargingPoint, data) =>
            new CPair[ChargingPointType, java.lang.Double](chargingPoint, data.prob)
          }
          .toVector
          .asJava
      )
      val partialTriggers = (1 to roundUniformly(chargingPointData.rate * timeStepByHour, rand).toInt)
        .foldLeft((timeBin, Vector.empty[ScheduleTrigger])) { case ((prevStartTime, triggers), _) =>
          val chargingPointType = distribution.sample()
          val data = chargingPointData.chargingPointMap(chargingPointType)
          val startTime = prevStartTime + roundUniformly(nextTimePoisson(chargingPointData.rate), rand).toInt
          val duration = roundUniformly(data.meanDuration + (rand.nextGaussian() * data.sdDuration), rand).toInt
          val soc = data.meanSOC + (rand.nextGaussian() * data.sdSOC)
          val activityType = getActivityType(chargingPointType)
          val taz = getBeamServices.beamScenario.tazTreeMap.getTAZ(tazId).get
          val destinationUtm = TAZTreeMap.randomLocationInTAZ(taz, rand)
          val vehicleType = getBeamVehicleType()
          val reservedFor = data.reservedFor.managerType match {
            case VehicleManager.TypeEnum.Household => VehicleManager.AnyManager
            case _                                 => data.reservedFor
          }
          val beamVehicle = getBeamVehicle(vehicleType, reservedFor, soc)
          val vehicleId = beamVehicle.id.toString
          val personId = Id.create(vehicleId.replace("VirtualCar", "VirtualPerson"), classOf[PersonAgent])
          val parkingInquiry = ParkingInquiry(
            SpaceTime(destinationUtm, startTime),
            activityType,
            reservedFor,
            Some(beamVehicle),
            None, // remainingTripData
            Some(personId),
            0.0, // valueOfTime
            duration,
            triggerId = triggerId
          )
          inquiryMap.put(parkingInquiry.requestId, parkingInquiry)
          (
            startTime,
            triggers :+ ScheduleTrigger(PlanParkingInquiryTrigger(startTime, parkingInquiry.requestId), self)
          )
        }
        ._2
      partialTriggers
    }.toVector
  }

  /**
    * get activity type
    * @param chargingType ChargingPointType
    * @return
    */
  protected def getActivityType(chargingType: ChargingPointType): ParkingActivityType = {
    if (chargingType.toString.toLowerCase.contains("home"))
      ParkingActivityType.Home
    else if (chargingType.toString.toLowerCase.contains("work"))
      ParkingActivityType.Work
    else
      ParkingActivityType.Wherever
  }

  /**
    * get Beam Vehicle Type
    * @return
    */
  protected def getBeamVehicleType(): BeamVehicleType = {
    BeamVehicleType(
      id = Id.create("VirtualCarType", classOf[BeamVehicleType]),
      seatingCapacity = 4,
      standingRoomCapacity = 0,
      lengthInMeter = 4.1,
      primaryFuelType = FuelType.Electricity,
      primaryFuelConsumptionInJoulePerMeter = 626,
      primaryFuelCapacityInJoule = 302234052,
      vehicleCategory = VehicleCategory.Car
    )
  }

  /**
    * get Beam Vehicle
    * @param vehicleType BeamVehicleType
    * @param reservedFor ReservedFor
    * @param soc State Of Charge In Double
    * @return
    */
  protected def getBeamVehicle(vehicleType: BeamVehicleType, reservedFor: ReservedFor, soc: Double): BeamVehicle = {
    val powerTrain = new Powertrain(vehicleType.primaryFuelConsumptionInJoulePerMeter)
    val nextId = VehicleIdGenerator.nextId
    val beamVehicle = new BeamVehicle(
      Id.create("VirtualCar-" + nextId, classOf[BeamVehicle]),
      powerTrain,
      vehicleType,
      new AtomicReference(reservedFor.managerId),
      randomSeed = rand.nextInt
    )
    beamVehicle.initializeFuelLevels(soc)
    beamVehicle
  }

  /**
    * summarizeAndSkimOrGetChargingData
    * @return map
    */
  protected def summarizeAndSkimOrGetChargingData(): Map[Id[TAZ], ChargingPointData] = {
    if (cnmConfig.scaleUp.enabled && simulatedEvents.nonEmpty) {
      val chargingDataSummary = simulatedEvents.par
        .map { case (tazId, chargingPointMap) =>
          val totEvents = chargingPointMap.map(_._2.durations.size).sum
          val rate = totEvents * scaleUpFactor / timeStepByHour
          tazId -> ChargingPointData(
            rate,
            chargingPointMap.flatMap {
              case (chargingPoint, data) if totEvents > 0 =>
                val prob = data.durations.size / totEvents.toDouble
                // Adding 10 seconds to avoid null duration
                val meanDur = 10 + (data.durations.sum / data.durations.size)
                val stdDevDur = Math.sqrt(data.durations.map(_ - meanDur).map(t => t * t).sum / data.durations.size)
                val meanFuel = data.soc.sum / data.soc.size
                val stdDevFuel = Math.sqrt(data.soc.map(_ - meanFuel).map(t => t * t).sum / data.soc.size)
                Some(
                  chargingPoint ->
                  ChargingDataSummary(
                    prob,
                    meanDur,
                    stdDevDur,
                    meanFuel,
                    stdDevFuel,
                    data.reservedFor
                  )
                )
              case _ => None
            }.toMap
          )
        }
        .seq
        .toMap
      simulatedEvents.clear()
      chargingDataSummary
    } else Map.empty[Id[TAZ], ChargingPointData]
  }

  /**
    * Collect Charging Data
    * @param stall Parking Stall
    * @param vehicle Beam Vehicle
    */
  protected def collectChargingData(stall: ParkingStall, vehicle: BeamVehicle): Unit = {
    if (cnmConfig.scaleUp.enabled && !vehicle.id.toString.contains("VirtualCar")) {
      val tazId = stall.tazId
      val chargingPoint = stall.chargingPointType.get
      val (duration, _) = vehicle.refuelingSessionDurationAndEnergyInJoulesForStall(Some(stall), None, None, None)
      val soc = vehicle.primaryFuelLevelInJoules / vehicle.beamVehicleType.primaryFuelCapacityInJoule
      simulatedEvents.synchronized {
        if (!simulatedEvents.contains(tazId))
          simulatedEvents.put(tazId, mutable.Map.empty[ChargingPointType, ChargingData])
        if (!simulatedEvents(tazId).contains(chargingPoint))
          simulatedEvents(tazId).put(
            chargingPoint,
            ChargingData(ListBuffer.empty[Int], ListBuffer.empty[Double], stall.reservedFor)
          )
        simulatedEvents(tazId)(chargingPoint).durations.append(duration)
        simulatedEvents(tazId)(chargingPoint).soc.append(soc)
      }
    }
  }
}

object ScaleUpCharging {
  case class PlanParkingInquiryTrigger(tick: Int, requestId: Int) extends Trigger
  case class PlanChargingUnplugRequestTrigger(tick: Int, beamVehicle: BeamVehicle, requestId: Int) extends Trigger

  case class ChargingData(durations: ListBuffer[Int], soc: ListBuffer[Double], reservedFor: ReservedFor)

  case class ChargingDataSummary(
    prob: Double,
    meanDuration: Int,
    sdDuration: Double,
    meanSOC: Double,
    sdSOC: Double,
    reservedFor: ReservedFor
  )
  case class ChargingPointData(rate: Double, chargingPointMap: Map[ChargingPointType, ChargingDataSummary])
}
