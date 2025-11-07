package nl.ctasoftware.crypto.ticker.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("v1/status")
public class StatusController {
    @GetMapping
    String status() {
        return "OK";
    }
}
