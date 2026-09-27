package com.tisseo.twin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "trips")
public class TripEntity {

    @Id
    @Column(name = "trip_id", nullable = false)
    private String tripId;

    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "shape_id")
    private String shapeId;

    @Column(name = "trip_headsign")
    private String tripHeadsign; // La destination affichée (ex: "Basso Cambo")

    @Column(name = "direction_id")
    private Integer directionId; // 0 = aller, 1 = retour

    public TripEntity() {
    }

    public TripEntity(String tripId, String routeId, String shapeId, String tripHeadsign, Integer directionId) {
        this.tripId = tripId;
        this.routeId = routeId;
        this.shapeId = shapeId;
        this.tripHeadsign = tripHeadsign;
        this.directionId = directionId;
    }

    public String getTripId() {
        return tripId;
    }

    public void setTripId(String tripId) {
        this.tripId = tripId;
    }

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getShapeId() {
        return shapeId;
    }

    public void setShapeId(String shapeId) {
        this.shapeId = shapeId;
    }

    public String getTripHeadsign() {
        return tripHeadsign;
    }

    public void setTripHeadsign(String tripHeadsign) {
        this.tripHeadsign = tripHeadsign;
    }

    public Integer getDirectionId() {
        return directionId;
    }

    public void setDirectionId(Integer directionId) {
        this.directionId = directionId;
    }
}