package com.tisseo.twin.dto;

public class VehicleState {
    private String tripId;
    private String routeId;
    private double lat;
    private double lon;
    private int delaySeconds;

    public VehicleState(String tripId, String routeId, double lat, double lon, int delaySeconds) {
        this.tripId = tripId;
        this.routeId = routeId;
        this.lat = lat;
        this.lon = lon;
        this.delaySeconds = delaySeconds;
    }

    // Getters
    public String getTripId() {
        return tripId;
    }

    public String getRouteId() {
        return routeId;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    public int getDelaySeconds() {
        return delaySeconds;
    }
}