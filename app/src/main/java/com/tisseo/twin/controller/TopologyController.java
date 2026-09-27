package com.tisseo.twin.controller;

import com.tisseo.twin.model.ShapeEntity;
import com.tisseo.twin.model.StopEntity;
import com.tisseo.twin.model.StopTimeEntity;
import com.tisseo.twin.model.TripEntity;
import com.tisseo.twin.repository.ShapeRepository;
import com.tisseo.twin.repository.StopRepository;
import com.tisseo.twin.repository.StopTimeRepository;
import com.tisseo.twin.repository.TripRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/topology")
public class TopologyController {

    private final StopRepository stopRepository;
    private final ShapeRepository shapeRepository;
    private final TripRepository tripRepository;
    private final StopTimeRepository stopTimeRepository;

    public TopologyController(StopRepository stopRepository, ShapeRepository shapeRepository,
            TripRepository tripRepository, StopTimeRepository stopTimeRepository) {
        this.stopRepository = stopRepository;
        this.shapeRepository = shapeRepository;
        this.tripRepository = tripRepository;
        this.stopTimeRepository = stopTimeRepository;

    }

    @GetMapping("/stops")
    public List<StopEntity> getAllStops() {
        return stopRepository.findAll();
    }

    @GetMapping("/shapes")
    public List<ShapeEntity> getAllShapes() {
        return shapeRepository.findAll();
    }

    @GetMapping("/trips/{tripId}")
    public TripEntity getTrip(@PathVariable String tripId) {
        return tripRepository.findById(tripId).orElse(null);
    }

    @GetMapping("/trips/{tripId}/schedules")
    public List<StopTimeEntity> getTripSchedules(@PathVariable String tripId) {
        return stopTimeRepository.findByTripIdOrderByStopSequenceAsc(tripId);
    }
}