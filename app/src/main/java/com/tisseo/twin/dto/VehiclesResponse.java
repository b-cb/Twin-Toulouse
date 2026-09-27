package com.tisseo.twin.dto;

import java.util.List;

public class VehiclesResponse {

    private List<VehicleState> buses;

    public VehiclesResponse(List<VehicleState> buses) {
        this.buses = buses;
    }

    public List<VehicleState> getBuses() {
        return buses;
    }
}
