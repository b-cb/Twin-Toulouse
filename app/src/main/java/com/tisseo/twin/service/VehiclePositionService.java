package com.tisseo.twin.service;

import com.tisseo.twin.dto.VehicleState;
import com.tisseo.twin.model.StopEntity;
import com.tisseo.twin.model.StopTimeEntity;
import com.tisseo.twin.model.TripEntity;
import com.tisseo.twin.repository.ShapeRepository;
import com.tisseo.twin.repository.StopRepository;
import com.tisseo.twin.repository.StopTimeRepository;
import com.tisseo.twin.repository.TripRepository;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calcule la position géographique précise d'un véhicule sur son tracé GTFS.
 *
 * Optimisations appliquées :
 * - Cache mémoire (ConcurrentHashMap) pour les shapes parsées.
 * - Cache Spring (@Cacheable / Caffeine) pour les requêtes DB répétitives.
 * - Distance Haversine correcte (remplace l'approximation euclidienne lat/lon).
 * - Projection sur le segment le plus proche (vs. point le plus proche).
 * - Support des horaires GTFS > 24h (trajets nocturnes, ex : "25:30:00").
 * - Calculs temporels en entiers (secondes) pour éviter les comparaisons de strings.
 */
@Service
public class VehiclePositionService {

    private static final double EARTH_RADIUS_M = 6_371_000.0;

    /** Cache en mémoire des shapes parsées : shapeId → liste de ShapePoint. */
    private final ConcurrentHashMap<String, List<ShapePoint>> shapeCache = new ConcurrentHashMap<>();

    private final StopTimeRepository stopTimeRepository;
    private final StopRepository stopRepository;
    private final TripRepository tripRepository;
    private final ShapeRepository shapeRepository;

    public VehiclePositionService(StopTimeRepository stopTimeRepository,
            StopRepository stopRepository,
            TripRepository tripRepository,
            ShapeRepository shapeRepository) {
        this.stopTimeRepository = stopTimeRepository;
        this.stopRepository = stopRepository;
        this.tripRepository = tripRepository;
        this.shapeRepository = shapeRepository;
    }

    // -------------------------------------------------------------------------
    // Classe interne : point de la shape
    // -------------------------------------------------------------------------

    public static class ShapePoint {
        /** Latitude en degrés décimaux. */
        public final double lat;
        /** Longitude en degrés décimaux. */
        public final double lon;
        /** Distance cumulée depuis le début de la shape (shape_dist_traveled GTFS). */
        public final double dist;

        public ShapePoint(double lat, double lon, double dist) {
            this.lat = lat;
            this.lon = lon;
            this.dist = dist;
        }
    }

    // -------------------------------------------------------------------------
    // Chargement DB avec cache Spring (@Cacheable / Caffeine)
    // -------------------------------------------------------------------------

    /** Horaires d'un trip, mis en cache (évite de requêter la DB à chaque tick). */
    @Cacheable("stopTimes")
    public List<StopTimeEntity> getSchedules(String tripId) {
        return stopTimeRepository.findByTripIdOrderByStopSequenceAsc(tripId);
    }

    /** Arrêt par ID, mis en cache. */
    @Cacheable("stops")
    public StopEntity getStop(String stopId) {
        return stopRepository.findById(stopId).orElse(null);
    }

    /** Trip par ID, mis en cache. */
    @Cacheable("trips")
    public TripEntity getTrip(String tripId) {
        return tripRepository.findById(tripId).orElse(null);
    }

    // -------------------------------------------------------------------------
    // Parsing et cache interne de la shape
    // -------------------------------------------------------------------------

    /**
     * Retourne la shape parsée depuis le cache mémoire, ou la parse et la met
     * en cache si c'est la première fois. Évite le re-parsing du CLOB à chaque appel.
     */
    private List<ShapePoint> getOrParseShape(String shapeId) {
        return shapeCache.computeIfAbsent(shapeId, id -> {
            var entity = shapeRepository.findById(id).orElse(null);
            if (entity == null) return List.of();
            return parseShape(entity.getCoordinates());
        });
    }

    /** Parse la chaîne "lat,lon,dist;..." en liste de ShapePoint. */
    private static List<ShapePoint> parseShape(String coords) {
        List<ShapePoint> points = new ArrayList<>();
        if (coords == null) return points;
        for (String pair : coords.split(";")) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            String[] data = trimmed.split(",");
            if (data.length >= 3) {
                points.add(new ShapePoint(
                        Double.parseDouble(data[0]),
                        Double.parseDouble(data[1]),
                        Double.parseDouble(data[2])));
            }
        }
        return points;
    }

    // -------------------------------------------------------------------------
    // Géométrie
    // -------------------------------------------------------------------------

    /**
     * Distance Haversine entre deux points géographiques, en mètres.
     * Remplace l'ancienne approximation euclidienne lat/lon qui introduisait
     * jusqu'à ~27% d'erreur en longitude à la latitude de Toulouse (43°N).
     */
    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Projette un arrêt sur la shape et retourne sa distance cumulée
     * (shape_dist_traveled) au pied de projection.
     *
     * Amélioration clé : on cherche le SEGMENT [pi, pi+1] le plus proche
     * au lieu du seul POINT le plus proche, ce qui donne une localisation
     * bien plus précise quand les points de shape sont espacés.
     */
    private static double getStopDistanceOnRoute(double stopLat, double stopLon, List<ShapePoint> route) {
        double minDistMeters = Double.MAX_VALUE;
        double bestDist = 0.0;

        for (int i = 0; i < route.size() - 1; i++) {
            ShapePoint p1 = route.get(i);
            ShapePoint p2 = route.get(i + 1);

            // Paramètre t ∈ [0,1] du pied de projection sur le segment [p1, p2]
            double segLat = p2.lat - p1.lat;
            double segLon = p2.lon - p1.lon;
            double segLenSq = segLat * segLat + segLon * segLon;

            double t = 0.0;
            if (segLenSq > 0) {
                t = ((stopLat - p1.lat) * segLat + (stopLon - p1.lon) * segLon) / segLenSq;
                t = Math.max(0.0, Math.min(1.0, t));
            }

            double projLat = p1.lat + t * segLat;
            double projLon = p1.lon + t * segLon;

            double d = haversineMeters(stopLat, stopLon, projLat, projLon);
            if (d < minDistMeters) {
                minDistMeters = d;
                // Distance cumulée au point projeté, interpolée entre p1 et p2
                bestDist = p1.dist + t * (p2.dist - p1.dist);
            }
        }
        return bestDist;
    }

    /**
     * Retourne [lat, lon] pour une distance cumulée donnée sur la shape,
     * par interpolation linéaire entre les deux points encadrants.
     */
    private static double[] getPositionAtDistance(List<ShapePoint> route, double targetDist) {
        for (int i = 0; i < route.size() - 1; i++) {
            ShapePoint p1 = route.get(i);
            ShapePoint p2 = route.get(i + 1);
            if (targetDist >= p1.dist && targetDist <= p2.dist) {
                double segmentLen = p2.dist - p1.dist;
                if (segmentLen == 0) return new double[]{p1.lat, p1.lon};
                double ratio = (targetDist - p1.dist) / segmentLen;
                return new double[]{
                        p1.lat + (p2.lat - p1.lat) * ratio,
                        p1.lon + (p2.lon - p1.lon) * ratio
                };
            }
        }
        ShapePoint last = route.get(route.size() - 1);
        return new double[]{last.lat, last.lon};
    }

    // -------------------------------------------------------------------------
    // Parsing des horaires GTFS (supporte > 24h)
    // -------------------------------------------------------------------------

    /**
     * Convertit un horaire GTFS en secondes depuis minuit.
     * Supporte les valeurs > "24:00:00" utilisées dans les trajets nocturnes.
     * L'ancien code utilisait LocalTime.parse() qui lève une exception sur ces valeurs.
     */
    private static int gtfsTimeToSeconds(String gtfsTime) {
        if (gtfsTime == null) return 0;
        String[] parts = gtfsTime.split(":");
        int h = Integer.parseInt(parts[0]);
        int m = Integer.parseInt(parts[1]);
        int s = Integer.parseInt(parts[2]);
        return h * 3600 + m * 60 + s;
    }

    // -------------------------------------------------------------------------
    // Méthode principale
    // -------------------------------------------------------------------------

    /**
     * Calcule la position géographique interpolée d'un véhicule sur sa shape GTFS.
     *
     * @param tripId        identifiant du trajet GTFS
     * @param delaySeconds  retard en secondes (négatif = avance)
     * @return VehicleState avec lat/lon calculés, ou null si hors plage horaire
     */
    public VehicleState calculateExactPosition(String tripId, int delaySeconds) {
        // Heure théorique selon l'horaire : heure réelle moins le retard
        int nowSeconds = LocalTime.now().toSecondOfDay();
        int theoreticalSeconds = nowSeconds - delaySeconds;

        List<StopTimeEntity> schedules = getSchedules(tripId);
        if (schedules == null || schedules.isEmpty()) return null;

        // Recherche des arrêts encadrants par comparaison en secondes (robuste > 24h)
        StopTimeEntity previousStop = null;
        StopTimeEntity nextStop = null;

        for (int i = 0; i < schedules.size(); i++) {
            StopTimeEntity current = schedules.get(i);
            if (gtfsTimeToSeconds(current.getDepartureTime()) > theoreticalSeconds) {
                nextStop = current;
                previousStop = (i > 0) ? schedules.get(i - 1) : null;
                break;
            }
        }

        // Cas : bus en attente au premier arrêt
        if (previousStop == null && nextStop != null) {
            StopEntity startEntity = getStop(nextStop.getStopId());
            if (startEntity != null) {
                return new VehicleState(tripId, null, startEntity.getStopLat(), startEntity.getStopLon(), delaySeconds);
            }
            return null;
        }

        // Cas : bus a terminé son trajet
        if (nextStop == null) return null;

        // Pourcentage de progression entre les deux arrêts encadrants
        double percentage = calculateTimePercentage(
                gtfsTimeToSeconds(previousStop.getDepartureTime()),
                gtfsTimeToSeconds(nextStop.getArrivalTime()),
                theoreticalSeconds);

        StopEntity prevEntity = getStop(previousStop.getStopId());
        StopEntity nextEntity = getStop(nextStop.getStopId());
        if (prevEntity == null || nextEntity == null) return null;

        TripEntity trip = getTrip(tripId);
        if (trip == null || trip.getShapeId() == null) return null;

        List<ShapePoint> route = getOrParseShape(trip.getShapeId());
        if (route.isEmpty()) return null;

        // Projection des arrêts sur la shape et interpolation de la position
        double prevDist = getStopDistanceOnRoute(prevEntity.getStopLat(), prevEntity.getStopLon(), route);
        double nextDist = getStopDistanceOnRoute(nextEntity.getStopLat(), nextEntity.getStopLon(), route);
        double targetDist = prevDist + (nextDist - prevDist) * percentage;

        double[] finalCoords = getPositionAtDistance(route, targetDist);
        return new VehicleState(tripId, null, finalCoords[0], finalCoords[1], delaySeconds);
    }

    /**
     * Calcule le ratio d'avancement temporel ∈ [0.0, 1.0] entre deux horaires.
     *
     * @param startSec   départ en secondes depuis minuit
     * @param endSec     arrivée en secondes depuis minuit
     * @param currentSec heure courante en secondes depuis minuit
     */
    private static double calculateTimePercentage(int startSec, int endSec, int currentSec) {
        int totalDuration = endSec - startSec;
        if (totalDuration <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, (double) (currentSec - startSec) / totalDuration));
    }
}