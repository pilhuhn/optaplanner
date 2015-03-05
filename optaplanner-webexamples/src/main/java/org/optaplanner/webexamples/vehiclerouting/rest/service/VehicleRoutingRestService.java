/*
 * Copyright 2015 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.webexamples.vehiclerouting.rest.service;

import java.awt.Color;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;

import org.optaplanner.core.api.domain.solution.Solution;
import org.optaplanner.core.api.score.buildin.hardsoft.HardSoftScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.SolverFactory;
import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.core.api.solver.event.SolverEventListener;
import org.optaplanner.core.config.solver.termination.TerminationConfig;
import org.optaplanner.examples.common.swingui.TangoColorFactory;
import org.optaplanner.examples.vehiclerouting.domain.Customer;
import org.optaplanner.examples.vehiclerouting.domain.Vehicle;
import org.optaplanner.examples.vehiclerouting.domain.VehicleRoutingSolution;
import org.optaplanner.examples.vehiclerouting.domain.location.Location;
import org.optaplanner.examples.vehiclerouting.persistence.VehicleRoutingImporter;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonCustomer;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonMessage;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonVehicleRoute;
import org.optaplanner.webexamples.vehiclerouting.rest.domain.JsonVehicleRoutingSolution;

@Path("/vehiclerouting")
public class VehicleRoutingRestService {

    public static final String SOLVER_CONFIG = "org/optaplanner/examples/vehiclerouting/solver/vehicleRoutingSolverConfig.xml";
    private static final String IMPORT_DATASET = "/org/optaplanner/webexamples/vehiclerouting/belgium-road-time-n50-k10.vrp";

    private SolverFactory solverFactory;
    // TODO After upgrading to JEE 7, replace ExecutorService by ManagedExecutorService:
    // @Resource(name = "DefaultManagedExecutorService")
    // private ManagedExecutorService executor;
    private ExecutorService executor;

    private Map<String, VehicleRoutingSolution> sessionSolutionMap;
    private Map<String, Solver> sessionSolverMap;

    @Context
    private HttpServletRequest request;

    @PostConstruct
    public void init() {
        solverFactory = SolverFactory.createFromXmlResource(SOLVER_CONFIG);
        // Always terminate a solver after 2 minutes
        TerminationConfig terminationConfig = new TerminationConfig();
        terminationConfig.setMinutesSpentLimit(2L);
        solverFactory.getSolverConfig().setTerminationConfig(terminationConfig);
        executor = Executors.newFixedThreadPool(4);
        sessionSolutionMap = new ConcurrentHashMap<String, VehicleRoutingSolution>();
        sessionSolverMap = new ConcurrentHashMap<String, Solver>();
    }

    @PreDestroy
    public void destroy() {
        for (Solver solver : sessionSolverMap.values()) {
            solver.terminateEarly();
        }
        executor.shutdown();
    }

    @GET
    @Path("/solution")
    @Produces("application/json")
    public JsonVehicleRoutingSolution getSolution() {
        VehicleRoutingSolution solution = retrieveOrCreateSolution();
        return convertToJsonVehicleRoutingSolution(solution);
    }

    protected VehicleRoutingSolution retrieveOrCreateSolution() {
        VehicleRoutingSolution solution = sessionSolutionMap.get(request.getSession().getId());
        if (solution == null) {
            URL unsolvedSolutionURL = getClass().getResource(IMPORT_DATASET);
            solution = (VehicleRoutingSolution) new VehicleRoutingImporter(true)
                    .readSolution(unsolvedSolutionURL);
            sessionSolutionMap.put(request.getSession().getId(), solution);
        }
        return solution;
    }

    protected JsonVehicleRoutingSolution convertToJsonVehicleRoutingSolution(VehicleRoutingSolution solution) {
        JsonVehicleRoutingSolution jsonSolution = new JsonVehicleRoutingSolution();
        jsonSolution.setName(solution.getName());
        List<JsonCustomer> jsonCustomerList = new ArrayList<JsonCustomer>(solution.getCustomerList().size());
        for (Customer customer : solution.getCustomerList()) {
            Location customerLocation = customer.getLocation();
            jsonCustomerList.add(new JsonCustomer(customerLocation.getName(),
                    customerLocation.getLatitude(), customerLocation.getLongitude(), customer.getDemand()));
        }
        jsonSolution.setCustomerList(jsonCustomerList);
        List<JsonVehicleRoute> jsonVehicleRouteList = new ArrayList<JsonVehicleRoute>(solution.getVehicleList().size());
        TangoColorFactory tangoColorFactory = new TangoColorFactory();
        for (Vehicle vehicle : solution.getVehicleList()) {
            JsonVehicleRoute jsonVehicleRoute = new JsonVehicleRoute();
            Location depotLocation = vehicle.getDepot().getLocation();
            jsonVehicleRoute.setDepotLocationName(depotLocation.getName());
            jsonVehicleRoute.setDepotLatitude(depotLocation.getLatitude());
            jsonVehicleRoute.setDepotLongitude(depotLocation.getLongitude());
            jsonVehicleRoute.setCapacity(vehicle.getCapacity());
            Color color = tangoColorFactory.pickColor(vehicle);
            jsonVehicleRoute.setHexColor(
                    String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()));
            Customer customer = vehicle.getNextCustomer();
            int demandTotal = 0;
            List<JsonCustomer> jsonVehicleCustomerList = new ArrayList<JsonCustomer>();
            while (customer != null) {
                Location customerLocation = customer.getLocation();
                demandTotal += customer.getDemand();
                jsonVehicleCustomerList.add(new JsonCustomer(customerLocation.getName(),
                        customerLocation.getLatitude(), customerLocation.getLongitude(), customer.getDemand()));
                customer = customer.getNextCustomer();
            }
            jsonVehicleRoute.setDemandTotal(demandTotal);
            jsonVehicleRoute.setCustomerList(jsonVehicleCustomerList);
            jsonVehicleRouteList.add(jsonVehicleRoute);
        }
        jsonSolution.setVehicleRouteList(jsonVehicleRouteList);
        HardSoftScore score = solution.getScore();
        jsonSolution.setFeasible(score == null ? false : score.isFeasible());
        jsonSolution.setDistance(score == null ? null
                : (-score.getSoftScore()) + " " + solution.getDistanceUnitOfMeasurement());
        return jsonSolution;
    }

    @POST
    @Path("/solution/solve")
    @Produces("application/json")
    public JsonMessage solve() {
        final Solver solver = solverFactory.buildSolver();
        final VehicleRoutingSolution solution = retrieveOrCreateSolution();
        executor.submit(new Runnable() {
            @Override
            public void run() {
                sessionSolverMap.put(request.getSession().getId(), solver);
                solver.addEventListener(new SolverEventListener() {
                    @Override
                    public void bestSolutionChanged(BestSolutionChangedEvent event) {
                        VehicleRoutingSolution bestSolution = (VehicleRoutingSolution) event.getNewBestSolution();
                        sessionSolutionMap.put(request.getSession().getId(), bestSolution);
                    }
                });
                solver.solve(solution);
                VehicleRoutingSolution bestSolution = (VehicleRoutingSolution) solver.getBestSolution();
                sessionSolutionMap.put(request.getSession().getId(), bestSolution);
                sessionSolverMap.remove(request.getSession().getId());
            }
        });
        return new JsonMessage("Solving started");
    }

    @POST
    @Path("/solution/terminateEarly")
    @Produces("application/json")
    public JsonMessage terminateEarly() {
        Solver solver = sessionSolverMap.remove(request.getSession().getId());
        if (solver != null) {
            solver.terminateEarly();
            return new JsonMessage("Solver terminating early.");
        }
        return new JsonMessage("Solver was already terminated.");
    }

}
