package beam.agentsim.agents

import akka.actor.FSM.Failure
import akka.actor.Props
import akka.pattern.{ask, pipe}
import beam.agentsim.Resource.ResourceIsAvailableNotification
import beam.agentsim.agents.BeamAgent._
import beam.agentsim.agents.PersonAgent.{Moving, PassengerScheduleEmptyTrigger, Waiting}
import beam.agentsim.agents.RideHailingAgent._
import beam.agentsim.agents.RideHailingManager.RideAvailableAck
import beam.agentsim.agents.TriggerUtils._
import beam.agentsim.agents.modalBehaviors.DrivesVehicle
import beam.agentsim.agents.vehicles.VehicleProtocol.{BecomeDriver, BecomeDriverSuccessAck}
import beam.agentsim.agents.vehicles.{ModifyPassengerScheduleAck, PassengerSchedule, ReservationResponse}
import beam.agentsim.events.SpaceTime
import beam.agentsim.scheduler.BeamAgentScheduler.CompletionNotice
import beam.agentsim.scheduler.TriggerWithId
import beam.router.BeamRouter.Location
import beam.router.RoutingModel
import beam.router.RoutingModel.{BeamTrip, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.{BeamServices, HasServices}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicle

import scala.concurrent.ExecutionContext.Implicits.global

object RideHailingAgent {
  val idPrefix: String = "rideHailingAgent"

  def props(services: BeamServices, eventsManager: EventsManager, rideHailingAgentId: Id[RideHailingAgent], vehicleId: Id[Vehicle], location: Coord) =
    Props(new RideHailingAgent(rideHailingAgentId, RideHailingAgentData(vehicleId, location), eventsManager, services))

  case class RideHailingAgentData(vehicleId: Id[Vehicle], location: Coord) extends BeamAgentData

  case object Idle extends BeamAgentState

  case object Traveling extends BeamAgentState

  case class PickupCustomer(confirmation: ReservationResponse, customerId: Id[Person], pickUpLocation: Location, destination: Location, trip2DestPlan: Option[BeamTrip], trip2CustPlan: Option[BeamTrip])

  case class DropOffCustomer(newLocation: SpaceTime)

  def isRideHailingLeg(currentLeg: EmbodiedBeamLeg): Boolean = {
    currentLeg.beamVehicleId.toString.contains("rideHailingVehicle")
  }

  def getRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Vector[RoutingModel.EmbodiedBeamLeg] = {
    chosenTrip.legs.filter(l => isRideHailingLeg(l))
  }

  def isRideHailingTrip(chosenTrip: EmbodiedBeamTrip): Boolean = {
    getRideHailingTrip(chosenTrip).nonEmpty
  }

}

class RideHailingAgent(override val id: Id[RideHailingAgent], override val data: RideHailingAgentData, val eventsManager: EventsManager, val beamServices: BeamServices)
  extends BeamAgent[RideHailingAgentData]
    with HasServices
    with DrivesVehicle[RideHailingAgentData] {
  override def logPrefix(): String = s"RideHailingAgent $id: "

  chainedWhen(Uninitialized) {
    case Event(TriggerWithId(InitializeTrigger(tick), triggerId), _: BeamAgentInfo[RideHailingAgentData]) =>
      val passengerSchedule = PassengerSchedule()
      self ! BecomeDriver(tick, id, Some(passengerSchedule))
      goto(PersonAgent.Waiting) replying completed(triggerId, schedule[PassengerScheduleEmptyTrigger](tick, self))
  }

  chainedWhen(Waiting) {
    case Event(TriggerWithId(PassengerScheduleEmptyTrigger(tick), triggerId), info) =>
      val response = beamServices.rideHailingManager ? ResourceIsAvailableNotification(info.data.vehicleId, SpaceTime(info.data.location, tick.toLong))
      response.mapTo[RideAvailableAck.type].map(result => CompletionNotice(triggerId)) pipeTo beamServices.schedulerRef
      stay()
  }

  chainedWhen(AnyState) {
    case Event(ModifyPassengerScheduleAck(Some(msgId)), _) =>
      stay
    case Event(BecomeDriverSuccessAck, _) =>
      stay
    case Event(Finish, _) =>
      stop
  }


  //// BOILERPLATE /////

  when(Waiting) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg"))
  }

  when(Moving) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg"))
  }

  when(AnyState) {
    case ev@Event(_, _) =>
      handleEvent(stateName, ev)
    case msg@_ =>
      stop(Failure(s"Unrecognized message $msg"))
  }

}


