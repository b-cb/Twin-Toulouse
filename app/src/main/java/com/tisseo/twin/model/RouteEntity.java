package com.tisseo.twin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.checkerframework.checker.signature.qual.Identifier;
import org.springframework.boot.autoconfigure.domain.EntityScan;

@Entity
@Table(name = "routes")
public class RouteEntity {

    @Id
    @Column(name = "route_id", nullable = false)
    private String routeId;

    @Column(name = "route_short_name")
    private String route_short_Name;

    @Column(name = "route_long_name")
    private String route_long_Name;

    @Column(name = "route_type")
    private Integer routeType;

    @Column(name = "route_color")
    private String routeColor;

    public RouteEntity() {
    };

    public RouteEntity(String routeId, String route_short_Name, String route_long_Name, Integer routeType,
            String routeColor) {
        this.routeId = routeId;
        this.route_short_Name = route_short_Name;
        this.route_long_Name = route_long_Name;
        this.routeType = routeType;
        this.routeColor = routeColor;
    };

    public String getrouteId() {
        return routeId;
    }

    public void setrouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getroute_short_Name() {
        return route_short_Name;
    }

    public void setroute_short_Name(String route_short_Name) {
        this.route_short_Name = route_short_Name;
    }

    public String getroute_long_Name() {
        return route_long_Name;
    }

    public void setroute_long_Name(String route_long_Name) {
        this.route_long_Name = route_long_Name;
    }

    public Integer getrouteType() {
        return routeType;
    }

    public void setrouteType(Integer routeType) {
        this.routeType = routeType;
    }

    public String getrouteColor() {
        return routeColor;
    }

    public void setrouteColor(String routeColor) {
        this.routeColor = routeColor;
    }

}
