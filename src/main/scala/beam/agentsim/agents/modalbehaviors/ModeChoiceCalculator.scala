package beam.agentsim.agents.modalbehaviors

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.mode._
import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person


import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * BEAM
  */
trait ModeChoiceCalculator extends HasServices {

  import ModeChoiceCalculator._

  implicit lazy val random: Random = new Random(
    beamServices.beamConfig.matsim.modules.global.randomSeed
  )

  def scaleTimeByVot(time: Double, beamMode: Option[BeamMode] = None, beamLeg: Option[EmbodiedBeamLeg] = None): Double = {
    time / 3600 * beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
  }

  def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Option[EmbodiedBeamTrip]

  def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: AttributesOfIndividual, destinationActivity: Option[Activity]): Double

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double

  def computeAllDayUtility(
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double

  final def chooseRandomAlternativeIndex(alternatives: Seq[EmbodiedBeamTrip]): Int = {
    if (alternatives.nonEmpty) {
      Random.nextInt(alternatives.size)
    } else {
      throw new IllegalArgumentException("Cannot choose from an empty choice set.")
    }
  }
}

object ModeChoiceCalculator {

  type ModeChoiceCalculatorFactory = AttributesOfIndividual => ModeChoiceCalculator

  def apply(classname: String, beamServices: BeamServices): ModeChoiceCalculatorFactory = {
    classname match {
      case "ModeChoiceLCCM" =>
        val lccm = new LatentClassChoiceModel(beamServices)
        (attributesOfIndividual: AttributesOfIndividual) =>
          attributesOfIndividual match {
            case AttributesOfIndividual(_, Some(modalityStyle), _, _, _, _, _) =>
              new ModeChoiceMultinomialLogit(
                beamServices,
                lccm.modeChoiceModels(Mandatory)(modalityStyle)
              )
            case _ =>
              throw new RuntimeException("LCCM needs people to have modality styles")
          }
      case "ModeChoiceTransitIfAvailable" =>
        _ =>
          new ModeChoiceTransitIfAvailable(beamServices)
      case "ModeChoiceDriveIfAvailable" =>
        _ =>
          new ModeChoiceDriveIfAvailable(beamServices)
      case "ModeChoiceRideHailIfAvailable" =>
        _ =>
          new ModeChoiceRideHailIfAvailable(beamServices)
      case "ModeChoiceUniformRandom" =>
        _ =>
          new ModeChoiceUniformRandom(beamServices)
      case "ModeChoiceMultinomialLogit" =>
        val logit = ModeChoiceMultinomialLogit.buildModelFromConfig(
          beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.mulitnomialLogit
        )
        _ =>
          new ModeChoiceMultinomialLogit(beamServices, logit)
    }
  }
  sealed trait ModeVotMultiplier
  case object Drive extends ModeVotMultiplier
  case object OnTransit extends ModeVotMultiplier
  case object Walk extends ModeVotMultiplier
  case object WalkToTransit extends ModeVotMultiplier // Separate from walking
  case object DriveToTransit extends ModeVotMultiplier
  case object RideHail extends ModeVotMultiplier // No separate ride hail to transit VOT
  case object Bike extends ModeVotMultiplier
  sealed trait timeSensitivity
  case object highSensitivity extends timeSensitivity
  case object lowSensitivity extends timeSensitivity
  sealed trait congestionLevel
  case object highCongestion extends congestionLevel
  case object lowCongestion extends congestionLevel
  sealed trait roadwayType
  case object highway extends roadwayType
  case object nonHighway extends roadwayType
  sealed trait automationLevel
  case object levelLE2 extends automationLevel
  case object level3 extends automationLevel
  case object level4 extends automationLevel
  case object level5 extends automationLevel
}
