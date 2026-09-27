package com.tisseo.twin.service;

import com.google.transit.realtime.GtfsRealtime;
import com.tisseo.twin.dto.VehicleState;
import com.tisseo.twin.dto.VehiclesResponse;
import com.tisseo.twin.repository.StopTimeRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Service
@RestController
public class LiveTrafficService {

    private List<VehicleState> currentVehicles = new ArrayList<>();
    private final VehiclePositionService vehiclePositionService;
    private final StopTimeRepository stopTimeRepository;

    /**
     * Lignes absentes du GTFS-RT Tisseo dont les positions sont calculées
     * depuis les horaires statiques GTFS (métro A, métro B).
     * Le tram T1 (line:68) est présent dans le GTFS-RT, inutile de l'ajouter ici.
     */
    private static final List<String> STATIC_POSITION_ROUTES = List.of("line:61", "line:69");

    public LiveTrafficService(VehiclePositionService vehiclePositionService,
                              StopTimeRepository stopTimeRepository) {
        this.vehiclePositionService = vehiclePositionService;
        this.stopTimeRepository = stopTimeRepository;
    }

    // Polling toutes les 30 secondes
    @Scheduled(initialDelay = 17000, fixedRate = 30000)
    public void fetchLiveTraffic() {
        try {
            System.out.println("📡 Téléchargement de l'état global du réseau (GTFS-RT)...");

            URL url = URI.create("https://api.tisseo.fr/opendata/gtfsrt/GtfsRt.pb").toURL();
            GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.parseFrom(url.openStream());

            List<VehicleState> updatedVehicles = new ArrayList<>();

            // ---------------------------------------------------------------
            // 1. Traitement du flux GTFS-RT (bus, tram, téléphérique)
            // ---------------------------------------------------------------
            int cntTripUpdate = 0, cntVehicle = 0, cntAlert = 0, cntEmpty = 0;

            for (GtfsRealtime.FeedEntity entity : feed.getEntityList()) {
                if (entity.hasTripUpdate()) {
                    cntTripUpdate++;
                    GtfsRealtime.TripUpdate tripUpdate = entity.getTripUpdate();
                    String tripId  = tripUpdate.getTrip().getTripId();
                    String routeId = tripUpdate.getTrip().getRouteId();

                    int delay = 0;
                    if (tripUpdate.getStopTimeUpdateCount() > 0) {
                        GtfsRealtime.TripUpdate.StopTimeUpdate nextStop = tripUpdate.getStopTimeUpdate(0);
                        if (nextStop.hasArrival() && nextStop.getArrival().hasDelay()) {
                            delay = nextStop.getArrival().getDelay();
                        }
                    }

                    VehicleState state = vehiclePositionService.calculateExactPosition(tripId, delay);
                    if (state != null) {
                        updatedVehicles.add(new VehicleState(
                                state.getTripId(), routeId,
                                state.getLat(), state.getLon(),
                                state.getDelaySeconds()));
                    }

                } else if (entity.hasVehicle()) {
                    cntVehicle++;
                    GtfsRealtime.VehiclePosition vp = entity.getVehicle();
                    if (vp.hasPosition() && vp.hasTrip()) {
                        String tripId  = vp.getTrip().getTripId();
                        String routeId = vp.getTrip().getRouteId();
                        double lat     = vp.getPosition().getLatitude();
                        double lon     = vp.getPosition().getLongitude();

                        VehicleState state = vehiclePositionService.calculateExactPosition(tripId, 0);
                        if (state != null) {
                            updatedVehicles.add(new VehicleState(
                                    state.getTripId(), routeId,
                                    state.getLat(), state.getLon(), 0));
                        } else {
                            updatedVehicles.add(new VehicleState(tripId, routeId, lat, lon, 0));
                        }
                    }
                } else if (entity.hasAlert()) {
                    cntAlert++;
                } else {
                    cntEmpty++;
                }
            }

            System.out.println("📊 GTFS-RT — TripUpdate: " + cntTripUpdate
                    + " | VehiclePosition: " + cntVehicle
                    + " | Alert: " + cntAlert
                    + " | Total: " + feed.getEntityCount());

            // ---------------------------------------------------------------
            // 2. Positions statiques pour les lignes absentes du GTFS-RT
            //    (Métro A line:61, Métro B line:69)
            // ---------------------------------------------------------------
            int metroCount = addStaticPositions(updatedVehicles);
            System.out.println("🚇 Positions statiques injectées (métro) : " + metroCount);

            this.currentVehicles = updatedVehicles;
            System.out.println("✅ Snapshot terminé : " + updatedVehicles.size() + " véhicules.");

        } catch (Exception e) {
            System.err.println("❌ Erreur lors de la récupération du fichier GtfsRt.pb");
            e.printStackTrace();
        }
    }

    /**
     * Calcule les positions des lignes absentes du GTFS-RT (métro A, B)
     * depuis les horaires GTFS statiques.
     *
     * Déduplication : le GTFS contient N variantes service_id pour chaque rame
     * (une par calendrier : lundi, mardi, période scolaire…). Deux trips avec
     * le même firstDeparture représentent la MÊME rame physique → on n'en garde qu'un.
     */
    private int addStaticPositions(List<VehicleState> target) {
        String nowStr = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        List<Object[]> rows = stopTimeRepository
                .findActiveTripIdsWithFirstDeparture(STATIC_POSITION_ROUTES, nowStr);

        // Déduplication : firstDeparture → tripId (on garde le premier rencontré)
        LinkedHashMap<String, String> uniqueByDeparture = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String tripId   = (String) row[0];
            String firstDep = (String) row[1];
            uniqueByDeparture.putIfAbsent(firstDep, tripId);
        }

        System.out.println("🚇 Trips actifs bruts : " + rows.size()
                + " → après déduplication : " + uniqueByDeparture.size());

        int added = 0;
        for (String tripId : uniqueByDeparture.values()) {
            VehicleState state = vehiclePositionService.calculateExactPosition(tripId, 0);
            if (state == null) continue;

            var trip = vehiclePositionService.getTrip(tripId);
            String routeId = trip != null ? trip.getRouteId() : null;

            target.add(new VehicleState(state.getTripId(), routeId,
                    state.getLat(), state.getLon(), 0));
            added++;
        }
        return added;
    }

    @GetMapping("/api/vehicles/live")
    public VehiclesResponse getLiveVehicles() {
        return new VehiclesResponse(currentVehicles);
    }
}