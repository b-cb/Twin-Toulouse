package com.tisseo.twin.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tisseo.twin.model.RouteEntity;
import com.tisseo.twin.model.ShapeEntity;
import com.tisseo.twin.model.StopEntity;
import com.tisseo.twin.model.StopTimeEntity;
import com.tisseo.twin.model.TripEntity;
import com.tisseo.twin.repository.RouteRepository;
import com.tisseo.twin.repository.StopRepository;
import com.tisseo.twin.repository.StopTimeRepository;
import com.tisseo.twin.repository.TripRepository;
import com.tisseo.twin.repository.ShapeRepository;

@Service
@Transactional
public class GtfsImportService {

    private final StopRepository stopRepository;
    private final RouteRepository routeRepository;
    private final ShapeRepository shapeRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;

    public GtfsImportService(StopRepository stopRepository, RouteRepository routeRepository,
            ShapeRepository shapeRepository, TripRepository tripRepository, StopTimeRepository stopTimeRepository) {
        this.stopRepository = stopRepository;
        this.routeRepository = routeRepository;
        this.shapeRepository = shapeRepository;
        this.tripRepository = tripRepository;
        this.stopTimeRepository = stopTimeRepository;

    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadStopsOnStartups() {
        try (InputStream is = getClass().getResourceAsStream("/gtfs/stops.txt")) {
            if (is == null) {
                System.err.println("Fichier stops.txt introuvable dans resources/gtfs/");
                return;
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build();

                List<StopEntity> stops = new ArrayList<>();
                for (CSVRecord record : csvFormat.parse(reader)) {
                    String stopId = record.get("stop_id");
                    String stopName = record.get("stop_name");
                    double lat = Double.parseDouble(record.get("stop_lat"));
                    double lon = Double.parseDouble(record.get("stop_lon"));

                    String wbRaw = record.get("wheelchair_boarding");
                    Integer wheelchairBoarding = (wbRaw != null && !wbRaw.isBlank())
                            ? Integer.parseInt(wbRaw.trim())
                            : null;

                    stops.add(new StopEntity(stopId, stopName, lat, lon, wheelchairBoarding));
                }

                stopRepository.saveAll(stops);
                System.out.println("Importation GTFS réussie : " + stops.size() + " arrêts enregistrés dans H2.");
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture du fichier stops.txt : " + e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadShapesOnStartup() {
        try (InputStream is = getClass().getResourceAsStream("/gtfs/shapes.txt")) {
            if (is == null) {
                System.err.println("Fichier shapes.txt introuvable dans /ressources/gtfs/");
                return;
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build();

                LinkedHashMap<String, StringBuilder> shapes = new LinkedHashMap<>();
                for (CSVRecord record : csvFormat.parse(reader)) {
                    String shapeId = record.get("shape_id");
                    String lat = record.get("shape_pt_lat");
                    String lon = record.get("shape_pt_lon");

                    String dist = "0.0";
                    if (record.isMapped("shape_dist_traveled")) {
                        String distRaw = record.get("shape_dist_traveled");
                        if (distRaw != null && !distRaw.isBlank()) {
                            dist = distRaw;
                        }
                    }

                    shapes.computeIfAbsent(shapeId, id -> new StringBuilder())
                            .append(lat)
                            .append(",")
                            .append(lon)
                            .append(",")
                            .append(dist)
                            .append(";");
                }

                List<ShapeEntity> entities = new ArrayList<>();

                for (Map.Entry<String, StringBuilder> entry : shapes.entrySet()) {
                    String shapeId = entry.getKey();
                    String coords = entry.getValue().toString();

                    // Optionnel : retirer le dernier point-virgule résiduel
                    if (coords.endsWith(";")) {
                        coords = coords.substring(0, coords.length() - 1);
                    }

                    entities.add(new ShapeEntity(shapeId, coords));
                }

                // Sauvegarde en base et affichage console
                shapeRepository.saveAll(entities);
                System.out.println(
                        "Importation GTFS réussie : " + entities.size() + " tracés (shapes) enregistrés dans H2.");
            }

        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture du fichier shapes.txt : " + e.getMessage());
        }

    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadRoutesOnStartups() {
        try (InputStream is = getClass().getResourceAsStream("/gtfs/routes.txt")) {
            if (is == null) {
                System.err.println("Fichier routes.txt introuvable dans resources/gtfs/");
                return;
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build();

                List<RouteEntity> routes = new ArrayList<>();
                for (CSVRecord record : csvFormat.parse(reader)) {
                    String routeId = record.get("route_id");
                    String routeShortName = record.get("route_short_name");
                    String routeLongName = record.get("route_long_name");

                    String routeTyperaw = record.get("route_type");
                    Integer routeType = Integer.parseInt(routeTyperaw);
                    String routeColor = record.get("route_color");

                    routes.add(new RouteEntity(routeId, routeShortName, routeLongName, routeType, routeColor));
                }

                routeRepository.saveAll(routes);
                System.out.println("Importation GTFS réussie : " + routes.size() + " lignes enregistrées dans H2.");
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture du fichier routes.txt : " + e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadTripsOnStartup() {
        try (InputStream is = getClass().getResourceAsStream("/gtfs/trips.txt")) {
            if (is == null) {
                System.err.println("Fichier trips.txt introuvable dans resources/gtfs/");
                return;
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build();

                List<TripEntity> trips = new ArrayList<>();

                for (CSVRecord record : csvFormat.parse(reader)) {
                    String tripId = record.get("trip_id");
                    String routeId = record.get("route_id");
                    String shapeId = record.isMapped("shape_id") ? record.get("shape_id") : null;
                    String headsign = record.isMapped("trip_headsign") ? record.get("trip_headsign") : null;

                    Integer directionId = null;
                    if (record.isMapped("direction_id")) {
                        String dirRaw = record.get("direction_id");
                        if (dirRaw != null && !dirRaw.isBlank()) {
                            directionId = Integer.parseInt(dirRaw.trim());
                        }
                    }

                    trips.add(new TripEntity(tripId, routeId, shapeId, headsign, directionId));
                }

                tripRepository.saveAll(trips);
                System.out.println("Importation GTFS réussie : " + trips.size() + " trips enregistrés dans H2.");
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture du fichier trips.txt : " + e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadStopTimesOnStartup() {
        try (InputStream is = getClass().getResourceAsStream("/gtfs/stop_times.txt")) {
            if (is == null) {
                System.err.println("Fichier stop_times.txt introuvable dans resources/gtfs/");
                return;
            }

            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true)
                        .setTrim(true)
                        .build();

                List<StopTimeEntity> batch = new ArrayList<>(50000);
                int totalCount = 0;
                int batchSize = 50000;

                System.out.println("Début de l'importation de stop_times.txt (cela peut prendre un peu de temps)...");

                for (CSVRecord record : csvFormat.parse(reader)) {
                    String tripId = record.get("trip_id");
                    String arrivalTime = record.get("arrival_time");
                    String departureTime = record.get("departure_time");
                    String stopId = record.get("stop_id");
                    Integer stopSequence = Integer.parseInt(record.get("stop_sequence"));

                    batch.add(new StopTimeEntity(tripId, arrivalTime, departureTime, stopId, stopSequence));

                    if (batch.size() >= batchSize) {
                        stopTimeRepository.saveAll(batch);
                        totalCount += batch.size();
                        System.out.println("... " + totalCount + " horaires insérés.");
                        batch.clear();
                    }
                }

                if (!batch.isEmpty()) {
                    stopTimeRepository.saveAll(batch);
                    totalCount += batch.size();
                }

                System.out.println(
                        "Importation GTFS réussie : " + totalCount + " horaires (stop_times) enregistrés dans H2.");
            }
        } catch (Exception e) {
            System.err.println("Erreur lors de la lecture du fichier stop_times.txt :");
            e.printStackTrace();
        }
    }

}