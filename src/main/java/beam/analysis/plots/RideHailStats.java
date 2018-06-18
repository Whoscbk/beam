package beam.analysis.plots;

import beam.agentsim.events.ModeChoiceEvent;
import beam.agentsim.events.PathTraversalEvent;
import beam.analysis.plots.modality.RideHailDistanceRowModel;
import beam.utils.DebugLib;
import org.matsim.api.core.v01.events.Event;
import org.matsim.core.controler.events.IterationEndsEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author abid
 */
public class RideHailStats implements IGraphStats {

    private static final String fileName = "RideHailStats";

    /**
     * Map < IterationNumber, < statsName,Distance > >
     * statsName = passengerVKT | repositioningVKT | deadHeadingVKT
     */
    private Map<Integer, Map<String, Double>> statsMap = new HashMap<>();
    private Map<String, List<PathTraversalEvent>> eventMap = new HashMap<>();

    @Override
    public void resetStats() {
        eventMap.clear();
    }

    @Override
    public void processStats(Event event) {
        if (event instanceof PathTraversalEvent) {
            String vehicleId = event.getAttributes().get(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID);
            if (vehicleId.toLowerCase().contains("ridehail")) {
                List<PathTraversalEvent> list = eventMap.getOrDefault(vehicleId, new ArrayList<>());

                list.add((PathTraversalEvent) event);
                eventMap.put(vehicleId, list);
            }
        }
    }

    @Override
    public void createGraph(IterationEndsEvent event) throws IOException {
        int reservationCount = 0;
        Map<RideHailDistanceRowModel.GraphType, Double> distanceTravelled = new HashMap<>();
        for (String vehicle : eventMap.keySet()) {
            List<PathTraversalEvent> list = eventMap.get(vehicle);
            int size = list.size();
            PathTraversalEvent[] arr = new PathTraversalEvent[size];
            arr = list.toArray(arr);
            for (int loopCounter = 0; loopCounter < size; loopCounter++) {
                Map<String, String> evAttr = arr[loopCounter].getAttributes();
                double newDistance = Double.parseDouble(evAttr.get(PathTraversalEvent.ATTRIBUTE_LENGTH));
                int numPass = Integer.parseInt(evAttr.get(PathTraversalEvent.ATTRIBUTE_NUM_PASS));
                if (numPass == 1) {
                    if ("car".equals(evAttr.get(PathTraversalEvent.ATTRIBUTE_MODE))) {
                        reservationCount++;
                    }
                    double distance = distanceTravelled.getOrDefault(RideHailDistanceRowModel.GraphType.PASSENGER_VKT, 0d);
                    distance = distance + newDistance;
                    distanceTravelled.put(RideHailDistanceRowModel.GraphType.PASSENGER_VKT, distance);
                } else if (numPass == 0 && loopCounter < (size - 1) && "1".equals(arr[loopCounter + 1].getAttributes().get(PathTraversalEvent.ATTRIBUTE_NUM_PASS))) {
                    double distance = distanceTravelled.getOrDefault(RideHailDistanceRowModel.GraphType.DEAD_HEADING_VKT, 0d);
                    distance = distance + newDistance;
                    distanceTravelled.put(RideHailDistanceRowModel.GraphType.DEAD_HEADING_VKT, distance);
                } else if (numPass == 0) {
                    double distance = distanceTravelled.getOrDefault(RideHailDistanceRowModel.GraphType.REPOSITIONING_VKT, 0d);
                    distance = distance + newDistance;
                    distanceTravelled.put(RideHailDistanceRowModel.GraphType.REPOSITIONING_VKT, distance);
                }
            }
        }
        RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(event.getIteration());
        if (model == null)
            model = new RideHailDistanceRowModel();
        model.setReservationCount(reservationCount);
        model.setRideHailDistanceStatMap(distanceTravelled);
        GraphUtils.RIDE_HAIL_REVENUE_MAP.put(event.getIteration(), model);
        writeToCSV(event);
    }

    @Override
    public void createGraph(IterationEndsEvent event, String graphType) throws IOException {
        throw new IOException("Not implemented");
    }

    private void writeToCSV(IterationEndsEvent event) throws IOException {

        String csvFileName = event.getServices().getControlerIO().getOutputFilename(fileName + ".csv");
        BufferedWriter out = null;
        try {
            out = new BufferedWriter(new FileWriter(new File(csvFileName)));

            String heading = "Iteration,rideHailRevenue,averageRideHailWaitingTime,totalRideHailWaitingTime,passengerVKT,repositioningVKT,deadHeadingVKT,averageSurgePriceLevel,maxSurgePriceLevel,reservationCount";
            out.write(heading);
            out.newLine();
            for (Integer key : GraphUtils.RIDE_HAIL_REVENUE_MAP.keySet()) {
                RideHailDistanceRowModel model = GraphUtils.RIDE_HAIL_REVENUE_MAP.get(key);
                double passengerVkt = model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.PASSENGER_VKT) == null ? 0 : model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.PASSENGER_VKT);
                double repositioningVkt = model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.REPOSITIONING_VKT) == null ? 0 : model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.REPOSITIONING_VKT);
                double deadheadingVkt = model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.DEAD_HEADING_VKT) == null ? 0 : model.getRideHailDistanceStatMap().get(RideHailDistanceRowModel.GraphType.DEAD_HEADING_VKT);
                double maxSurgePricingLevel = model.getMaxSurgePricingLevel();
                double totalSurgePricingLevel = model.getTotalSurgePricingLevel();
                double surgePricingLevelCount = model.getSurgePricingLevelCount();
                double averageSurgePricing = surgePricingLevelCount == 0 ? 0 : totalSurgePricingLevel / surgePricingLevelCount;
                int reservationCount = model.getReservationCount();
                out.append("" + key);
                out.append("," + model.getRideHailRevenue());
                out.append("," + model.getRideHailWaitingTimeSum() / model.getTotalRideHailCount());
                out.append("," + model.getRideHailWaitingTimeSum());
                out.append("," + passengerVkt/1000);
                out.append("," + repositioningVkt/1000);
                out.append("," + deadheadingVkt/1000);
                out.append("," + averageSurgePricing);
                out.append("," + maxSurgePricingLevel);
                out.append("," + reservationCount);
                out.newLine();
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
