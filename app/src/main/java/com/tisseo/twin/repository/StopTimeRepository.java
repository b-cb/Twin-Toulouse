package com.tisseo.twin.repository;

import com.tisseo.twin.model.StopTimeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StopTimeRepository extends JpaRepository<StopTimeEntity, Long> {

    // Récupère les arrêts d'un trajet ordonnés par séquence
    List<StopTimeEntity> findByTripIdOrderByStopSequenceAsc(String tripId);

    /**
     * Retourne les [tripId, firstDeparture] actifs à l'heure courante pour une liste de route_id.
     * Un trip est "actif" si : MIN(departureTime) <= now <= MAX(arrivalTime).
     * On retourne aussi firstDeparture pour permettre la déduplication côté Java :
     * plusieurs tripId avec le même firstDeparture sont des variantes service_id
     * de la même rame physique.
     */
    @Query("SELECT st.tripId, MIN(st.departureTime) FROM StopTimeEntity st " +
           "WHERE st.tripId IN " +
               "(SELECT t.tripId FROM TripEntity t WHERE t.routeId IN :routeIds) " +
           "GROUP BY st.tripId " +
           "HAVING MIN(st.departureTime) <= :now AND MAX(st.arrivalTime) >= :now " +
           "ORDER BY MIN(st.departureTime)")
    List<Object[]> findActiveTripIdsWithFirstDeparture(
            @Param("routeIds") List<String> routeIds,
            @Param("now") String now);
}