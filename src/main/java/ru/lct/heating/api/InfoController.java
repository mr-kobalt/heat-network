package ru.lct.heating.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class InfoController {

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of(
                "service", "heating-routing-service",
                "version", "0.1.0",
                "status", "skeleton");
    }
}
