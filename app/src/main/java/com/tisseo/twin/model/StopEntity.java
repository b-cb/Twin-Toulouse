package com.tisseo.twin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.checkerframework.checker.signature.qual.Identifier;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@Entity
@Table(name = "stops")
public class StopEntity {

    @Id
    @Column(name = "stop_id", nullable = false)
    private String stopId;

    @Column(name = "stop_name")
    private String stopName;

    @Column(name = "stop_lat")
    private double stopLat;

    @Column(name = "stop_lon")
    private double stopLon;

    @Column(name = "wheelchair_boarding")
    private Integer wheelchairBoarding;

    public StopEntity() {
    };

    public StopEntity(String stopId, String stopName, double stopLat, double stopLon, Integer wheelchairBoarding) {
        this.stopId = stopId;
        this.stopName = stopName;
        this.stopLat = stopLat;
        this.stopLon = stopLon;
        this.wheelchairBoarding = wheelchairBoarding;
    };

    public String getStopId() {
        return stopId;
    }

    public void setStopId(String stopId) {
        this.stopId = stopId;
    }

    public String getStopName() {
        return stopName;
    }

    public void setStopName(String stopName) {
        this.stopName = stopName;
    }

    public double getStopLat() {
        return stopLat;
    }

    public void setStopLat(double stopLat) {
        this.stopLat = stopLat;
    }

    public double getStopLon() {
        return stopLon;
    }

    public void setStopLon(double stopLon) {
        this.stopLon = stopLon;
    }

    public Integer getWheelchairBoarding() {
        return wheelchairBoarding;
    }

    public void setWheelchairBoarding(Integer wheelchairBoarding) {
        this.wheelchairBoarding = wheelchairBoarding;
    }

}
