package com.tisseo.twin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "shapes")
public class ShapeEntity {

    @Id
    @Column(name = "shape_id", nullable = false)
    private String shapeId;

    @Lob
    @Column(name = "coordinates", columnDefinition = "CLOB")
    private String coordinates; // Format: "lat,lon;lat,lon;lat,lon"

    public ShapeEntity() {
    }

    public ShapeEntity(String shapeId, String coordinates) {
        this.shapeId = shapeId;
        this.coordinates = coordinates;
    }

    public String getShapeId() {
        return shapeId;
    }

    public void setShapeId(String shapeId) {
        this.shapeId = shapeId;
    }

    public String getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(String coordinates) {
        this.coordinates = coordinates;
    }
}