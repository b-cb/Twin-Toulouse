package com.tisseo.twin.repository;

import com.tisseo.twin.model.TripEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TripRepository extends JpaRepository<TripEntity, String> {
    List<TripEntity> findByRouteId(String routeId);
}