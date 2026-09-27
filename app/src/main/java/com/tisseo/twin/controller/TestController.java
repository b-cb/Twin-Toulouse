package com.tisseo.twin.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private final RestClient restClient;

    public TestController(RestClient restClient) {
        this.restClient = restClient;
    }

    @GetMapping("/networks")
    public String testNetworks() {
        return (restClient.get().uri("/networks.json").retrieve().body(String.class));
    }
}
