package nl.ctasoftware.crypto.ticker.server.controller;

import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("v1/screen")
public class ScreenController {
    final ImageService imageService;

    public ScreenController(ImageService imageService) {
        this.imageService = imageService;
    }
}
