package ch.sbb.matsim.analysis.skims;

import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorRoute;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptor;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorCore.TravelInfo;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorData;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.core.utils.misc.Counter;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.opengis.feature.simple.SimpleFeature;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;

/**
 * Calculates zone-to-zone matrices containing a number of performance indicators related to public transport.
 *
 * Amongst the performance indicators are:
 * - travel time (measured from the first departure to the last arrival, without access/egress time)
 * - travel distance (measured from first departure to last arrival, without access/egress time)
 * - access time
 * - egress time
 * - perceived frequency
 * - share of travel time within trains
 * - share of travel distance within trains
 *
 * The main idea to calculate the frequencies follows the rooftop-algorithm from Niek Guis (ca. 2015).
 *
 * Idea of the algorithm for a single origin-destination (OD) pair:
 * - Given a time window (e.g. 07:00 - 08:00),
 * - find all possible (useful) connections between O and D
 * - for each minute in the time window, calculate the required adaption time to catch the next best connection (can be x minutes earlier or later)
 * - average the minutely adaption times over the time window
 * - based on the average adaption time, the service frequency can be calculated
 *
 * Idea of the algorithm for a full zone-to-zone matrix:
 * - given n points per zone
 * - for each point, find the possible stops to be used as departure or arrival stops.
 * - for each zone-to-zone combination, calculate the average adaption time to travel from each point to each other point in the destination zone.
 * - this results in n x n average adaption travel times per zone-to-zone combination.
 * - average the n x n adaption times and store this value as the zone-to-zone adaption time.
 *
 * A basic implementation for calculating the travel times between m zones would result in m^2 * n^2 pt route calculations,
 * which could take a very long time. The actual algorithm makes use of LeastCostPathTrees, reducing the computational effort down
 * to the calculation of m*n LeastCostPathTrees. In addition, it supports running the calculation in parallel to reduce the time
 * required to compute one matrix.
 *
 * If no connection can be found between two zones (can happen when there is no transit stop in a zone),
 * the corresponding matrix cells contain the value "0" for the perceived frequency, and "Infinity" for all other skim matrices.
 *
 * @author mrieser / SBB
 */
public class PTSkimMatrices {

    private PTSkimMatrices() {
    }

    public static <T> PTSkimMatrices.PtIndicators<T> calculateSkimMatrices(SwissRailRaptorData raptorData, Map<T, SimpleFeature> zones, Map<T, Coord[]> coordsPerZone, double minDepartureTime, double maxDepartureTime, double stepSize_seconds, RaptorParameters parameters, int numberOfThreads, BiPredicate<TransitLine, TransitRoute> trainDetector) {
        // prepare calculation
        PtIndicators<T> pti = new PtIndicators<>(zones.keySet());

        // do calculation
        ConcurrentLinkedQueue<T> originZones = new ConcurrentLinkedQueue<>(zones.keySet());

        Counter counter = new Counter("PT-FrequencyMatrix-" + Time.writeTime(minDepartureTime) + "-" + Time.writeTime(maxDepartureTime) + " zone ", " / " + zones.size());
        Thread[] threads = new Thread[numberOfThreads];
        for (int i = 0; i < numberOfThreads; i++) {
            SwissRailRaptor raptor = new SwissRailRaptor(raptorData, null, null, null);
            RowWorker<T> worker = new RowWorker<>(originZones, zones.keySet(), coordsPerZone, pti, raptor, parameters, minDepartureTime, maxDepartureTime, stepSize_seconds, counter, trainDetector);
            threads[i] = new Thread(worker, "PT-FrequencyMatrix-" + Time.writeTime(minDepartureTime) + "-" + Time.writeTime(maxDepartureTime) + "-" + i);
            threads[i].start();
        }

        // wait until all threads have finished
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        for (T fromZoneId : zones.keySet()) {
            for (T toZoneId : zones.keySet()) {
                float count = pti.dataCountMatrix.get(fromZoneId, toZoneId);
                float avgFactor = 1.0f / count;
                float adaptionTime = pti.adaptionTimeMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.travelTimeMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.accessTimeMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.egressTimeMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.trainDistanceShareMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.trainTravelTimeShareMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                pti.transferCountMatrix.multiply(fromZoneId, toZoneId, avgFactor);
                float frequency = (float) ((maxDepartureTime - minDepartureTime) / adaptionTime / 4.0);
                pti.frequencyMatrix.set(fromZoneId, toZoneId, frequency);
            }
        }

        return pti;
    }

    static class RowWorker<T> implements Runnable {
        private final ConcurrentLinkedQueue<T> originZones;
        private final Set<T> destinationZones;
        private final Map<T, Coord[]> coordsPerZone;
        private final PtIndicators<T> pti;
        private final SwissRailRaptor raptor;
        private final RaptorParameters parameters;
        private final double minDepartureTime;
        private final double maxDepartureTime;
        private final double stepSize;
        private final Counter counter;
        private final BiPredicate<TransitLine, TransitRoute> trainDetector;

        RowWorker(ConcurrentLinkedQueue<T> originZones, Set<T> destinationZones, Map<T, Coord[]> coordsPerZone, PtIndicators<T> pti, SwissRailRaptor raptor, RaptorParameters parameters, double minDepartureTime, double maxDepartureTime, double stepSize, Counter counter, BiPredicate<TransitLine, TransitRoute> trainDetector) {
            this.originZones = originZones;
            this.destinationZones = destinationZones;
            this.coordsPerZone = coordsPerZone;
            this.pti = pti;
            this.raptor = raptor;
            this.parameters = parameters;
            this.minDepartureTime = minDepartureTime;
            this.maxDepartureTime = maxDepartureTime;
            this.stepSize = stepSize;
            this.counter = counter;
            this.trainDetector = trainDetector;
        }

        public void run() {
            while (true) {
                T fromZoneId = this.originZones.poll();
                if (fromZoneId == null) {
                    return;
                }

                this.counter.incCounter();
                Coord[] fromCoords = this.coordsPerZone.get(fromZoneId);
                if (fromCoords != null) {
                    for (Coord fromCoord : fromCoords) {
                        calcForRow(fromZoneId, fromCoord);
                    }
                } else {
                    // this might happen if a zone has no geometry, for whatever reason...
                    for (T toZoneId : this.destinationZones) {
                        invalidateEntries(fromZoneId, toZoneId);
                    }
                }

            }
        }

        private void calcForRow(T fromZoneId, Coord fromCoord) {
            double walkSpeed = this.parameters.getBeelineWalkSpeed();

            Collection<TransitStopFacility> fromStops = findStopCandidates(fromCoord, this.raptor, this.parameters);
            Map<Id<TransitStopFacility>, Double> accessTimes = new HashMap<>();
            for (TransitStopFacility stop : fromStops) {
                double distance = CoordUtils.calcEuclideanDistance(fromCoord, stop.getCoord());
                double accessTime = distance / walkSpeed;
                accessTimes.put(stop.getId(), accessTime);
            }

            List<Map<Id<TransitStopFacility>, TravelInfo>> trees = new ArrayList<>();

            for (double time = this.minDepartureTime; time < this.maxDepartureTime; time += this.stepSize) {
                Map<Id<TransitStopFacility>, TravelInfo> tree = this.raptor.calcTree(fromStops, time, this.parameters);
                trees.add(tree);
            }

            for (T toZoneId : this.destinationZones) {
                Coord[] toCoords = this.coordsPerZone.get(toZoneId);
                if (toCoords != null) {
                    for (Coord toCoord : toCoords) {
                        calcForOD(fromZoneId, fromCoord, toZoneId, toCoord, accessTimes, trees);
                    }
                } else {
                    // this might happen if a zone has no geometry, for whatever reason...
                    invalidateEntries(fromZoneId, toZoneId);
                }
            }
        }

        private void calcForOD(T fromZoneId, Coord fromCoord, T toZoneId, Coord toCoord, Map<Id<TransitStopFacility>, Double> accessTimes, List<Map<Id<TransitStopFacility>, TravelInfo>> trees) {
            double walkSpeed = this.parameters.getBeelineWalkSpeed();

            Collection<TransitStopFacility> toStops = findStopCandidates(toCoord, this.raptor, this.parameters);
            Map<Id<TransitStopFacility>, Double> egressTimes = new HashMap<>();
            for (TransitStopFacility stop : toStops) {
                double distance = CoordUtils.calcEuclideanDistance(stop.getCoord(), toCoord);
                double egressTime = distance / walkSpeed;
                egressTimes.put(stop.getId(), egressTime);
            }

            List<ODConnection> connections = buildODConnections(trees, egressTimes);
            if (connections.isEmpty()) {
                invalidateEntries(fromZoneId, toZoneId);
                return;
            }

            connections = sortAndFilterConnections(connections);

            double avgAdaptionTime = calcAverageAdaptionTime(connections, minDepartureTime, maxDepartureTime);

            this.pti.adaptionTimeMatrix.add(fromZoneId, toZoneId, (float) avgAdaptionTime);

            ODConnection fastestConnection = findFastestConnection(connections);

            float accessTime = accessTimes.get(fastestConnection.travelInfo.departureStop).floatValue();
            float egressTime = (float) fastestConnection.egressTime;
            float transferCount = (float) fastestConnection.transferCount;
            float travelTime = (float) fastestConnection.totalTravelTime();

            double totalDistance = 0;
            double trainDistance = 0;
            double totalInVehTime = 0;
            double trainInVehTime = 0;

            RaptorRoute route = fastestConnection.travelInfo.getRaptorRoute();
            for (RaptorRoute.RoutePart part : route.getParts()) {
                if (part.line != null) {
                    // it's a non-transfer part, an actual pt stage

                    boolean isTrain = this.trainDetector.test(part.line, part.route);
                    double inVehicleTime = part.arrivalTime - part.boardingTime;

                    totalDistance += part.distance;
                    totalInVehTime += inVehicleTime;

                    if (isTrain) {
                        trainDistance += part.distance;
                        trainInVehTime += inVehicleTime;
                    }
                }
            }

            float trainShareByTravelTime = (float) (trainInVehTime / totalInVehTime);
            float trainShareByDistance = (float) (trainDistance / totalDistance);

            this.pti.accessTimeMatrix.add(fromZoneId, toZoneId, accessTime);
            this.pti.egressTimeMatrix.add(fromZoneId, toZoneId, egressTime);
            this.pti.transferCountMatrix.add(fromZoneId, toZoneId, transferCount);
            this.pti.travelTimeMatrix.add(fromZoneId, toZoneId, travelTime);
            this.pti.trainDistanceShareMatrix.add(fromZoneId, toZoneId, trainShareByDistance);
            this.pti.trainTravelTimeShareMatrix.add(fromZoneId, toZoneId, trainShareByTravelTime);

            this.pti.dataCountMatrix.add(fromZoneId, toZoneId, 1);
        }

        private List<ODConnection> buildODConnections(List<Map<Id<TransitStopFacility>, TravelInfo>> trees,  Map<Id<TransitStopFacility>, Double> egressTimes) {
            List<ODConnection> connections = new ArrayList<>();

            for (Map<Id<TransitStopFacility>, TravelInfo> tree : trees) {
                for (Map.Entry<Id<TransitStopFacility>, Double> egressEntry : egressTimes.entrySet()) {
                    Id<TransitStopFacility> egressStopId = egressEntry.getKey();
                    Double egressTime = egressEntry.getValue();
                    TravelInfo info = tree.get(egressStopId);
                    if (info != null) {
                        ODConnection connection = new ODConnection(info.ptDepartureTime, info.ptTravelTime, info.accessTime, egressTime, info.transferCount, info);
                        connections.add(connection);
                    }
                }
            }

            return connections;
        }

        static List<ODConnection> sortAndFilterConnections(List<ODConnection> connections) {
            connections.sort((c1, c2) -> Double.compare((c1.departureTime - c1.accessTime), (c2.departureTime - c2.accessTime)));

            // step forward through all connections and figure out which can be ignore because the earlier one is better
            List<ODConnection> filteredConnections1 = new ArrayList<>();
            ODConnection earlierConnection = null;
            for (ODConnection connection : connections) {
                if (earlierConnection == null) {
                    filteredConnections1.add(connection);
                    earlierConnection = connection;
                } else {
                    double timeDiff = (connection.departureTime - connection.accessTime) - (earlierConnection.departureTime - earlierConnection.accessTime);
                    if (earlierConnection.totalTravelTime() + timeDiff > connection.totalTravelTime()) {
                        // connection is better to earlierConnection, use it
                        filteredConnections1.add(connection);
                        earlierConnection = connection;
                    }
                }
            }

            // now step backwards through the remaining connections and figure out which can be ignored because the later one is better
            List<ODConnection> filteredConnections = new ArrayList<>();
            ODConnection laterConnection = null;

            for (int i = filteredConnections1.size() - 1; i >= 0; i--) {
                ODConnection connection = filteredConnections1.get(i);
                if (laterConnection == null) {
                    filteredConnections.add(connection);
                    laterConnection = connection;
                } else {
                    double timeDiff = (laterConnection.departureTime - laterConnection.accessTime) - (connection.departureTime - connection.accessTime);
                    if (laterConnection.totalTravelTime() + timeDiff > connection.totalTravelTime()) {
                        // connection is better to laterConnection, use it
                        filteredConnections.add(connection);
                        laterConnection = connection;
                    }
                }
            }

            Collections.reverse(filteredConnections);
            // now the filtered connections are in ascending departure time order
            return filteredConnections;
        }

        private ODConnection findFastestConnection(List<ODConnection> connections) {
            ODConnection fastest = null;
            for (ODConnection c : connections) {
                if (fastest == null || c.travelTime < fastest.travelTime) {
                    fastest = c;
                }
            }
            return fastest;
        }

        static double calcAverageAdaptionTime(List<ODConnection> connections, double minDepartureTime, double maxDepartureTime) {
            double prevDepartureTime = Double.NaN;
            double nextDepartureTime = Double.NaN;

            Iterator<ODConnection> connectionIterator = connections.iterator();
            if (connectionIterator.hasNext()) {
                ODConnection connection = connectionIterator.next();
                nextDepartureTime = connection.departureTime - connection.accessTime;
            }

            double sum = 0.0;
            int count = 0;
            for (double time = minDepartureTime; time < maxDepartureTime; time += 60.0) {
                double adaptionTime;

                if (time >= nextDepartureTime) {
                    prevDepartureTime = nextDepartureTime;
                    if (connectionIterator.hasNext()) {
                        ODConnection connection = connectionIterator.next();
                        nextDepartureTime = connection.departureTime - connection.accessTime;
                    } else {
                        nextDepartureTime = Double.NaN;
                    }
                }

                if (Double.isNaN(prevDepartureTime)) {
                    adaptionTime = nextDepartureTime - time;
                } else if (Double.isNaN(nextDepartureTime)) {
                    adaptionTime = time - prevDepartureTime;
                } else {
                    adaptionTime = Math.min(time - prevDepartureTime, nextDepartureTime - time);
                }

                sum += adaptionTime;
                count++;
            }
            return sum / count;
        }

        private static Collection<TransitStopFacility> findStopCandidates(Coord coord, SwissRailRaptor raptor, RaptorParameters parameters) {
            Collection<TransitStopFacility> stops = raptor.getUnderlyingData().findNearbyStops(coord.getX(), coord.getY(), parameters.getSearchRadius());
            if (stops.isEmpty()) {
                TransitStopFacility nearest = raptor.getUnderlyingData().findNearestStop(coord.getX(), coord.getY());
                double nearestStopDistance = CoordUtils.calcEuclideanDistance(coord, nearest.getCoord());
                stops = raptor.getUnderlyingData().findNearbyStops(coord.getX(), coord.getY(), nearestStopDistance + parameters.getExtensionRadius());
            }
            return stops;
        }

        private void invalidateEntries(T fromZone, T toZone) {
            this.pti.adaptionTimeMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.frequencyMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.travelTimeMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.accessTimeMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.egressTimeMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.transferCountMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.trainDistanceShareMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
            this.pti.trainTravelTimeShareMatrix.set(fromZone, toZone, Float.POSITIVE_INFINITY);
        }

    }

    static class ODConnection {
        final double departureTime;
        final double travelTime;
        final double accessTime;
        final double egressTime;
        final double transferCount;
        final TravelInfo travelInfo;

        ODConnection(double departureTime, double travelTime, double accessTime, double egressTime, double transferCount, TravelInfo info) {
            this.departureTime = departureTime;
            this.travelTime = travelTime;
            this.accessTime = accessTime;
            this.egressTime = egressTime;
            this.transferCount = transferCount;
            this.travelInfo = info;
        }

        double totalTravelTime() {
            return this.accessTime + this.travelTime + this.egressTime;
        }
    }

    public static class PtIndicators<T> {
        public final FloatMatrix<T> adaptionTimeMatrix;
        public final FloatMatrix<T> frequencyMatrix;

        public final FloatMatrix<T> travelTimeMatrix;
        public final FloatMatrix<T> accessTimeMatrix;
        public final FloatMatrix<T> egressTimeMatrix;
        public final FloatMatrix<T> transferCountMatrix;
        public final FloatMatrix<T> trainTravelTimeShareMatrix;
        public final FloatMatrix<T> trainDistanceShareMatrix;

        public final FloatMatrix<T> dataCountMatrix; // how many values/routes were taken into account to calculate the averages

        PtIndicators(Set<T> zones) {
            this.adaptionTimeMatrix = new FloatMatrix<>(zones, 0);
            this.frequencyMatrix = new FloatMatrix<>(zones, 0);

            this.travelTimeMatrix = new FloatMatrix<>(zones, 0);
            this.accessTimeMatrix = new FloatMatrix<>(zones, 0);
            this.egressTimeMatrix = new FloatMatrix<>(zones, 0);
            this.transferCountMatrix = new FloatMatrix<>(zones, 0);
            this.dataCountMatrix = new FloatMatrix<>(zones, 0);
            this.trainTravelTimeShareMatrix = new FloatMatrix<>(zones, 0);
            this.trainDistanceShareMatrix = new FloatMatrix<>(zones, 0);
        }
    }

}
