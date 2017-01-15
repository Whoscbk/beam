package beam.playground.metasim.agents.states;

import java.util.LinkedList;
import java.util.List;

import beam.playground.metasim.agents.BeamAgent;
import beam.playground.metasim.agents.PersonAgent;
import beam.playground.metasim.scheduler.ActionCallBack;
import beam.playground.metasim.services.BeamServices;

public class EnterExitInActivity extends StateEnterExitListener.Default {

	public EnterExitInActivity(BeamServices beamServices) {
		super(beamServices);
	}

	@Override
	public List<ActionCallBack> notifyOfStateEntry(BeamAgent agent) {
		PersonAgent personAgent = (PersonAgent)agent;
		double activityEndTime = personAgent.getPlanTracker().getCurrentOrNextActivity().getEndTime();
		return beamServices.getScheduler().createCallBackMethod(activityEndTime, agent, "EndActivity", this.getClass());
	}

	@Override
	public List<ActionCallBack> notifyOfStateExit(BeamAgent agent) {
		return new LinkedList<>();
	}

}
