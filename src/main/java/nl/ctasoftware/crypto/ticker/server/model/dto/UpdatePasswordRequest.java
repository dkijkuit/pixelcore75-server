package nl.ctasoftware.crypto.ticker.server.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdatePasswordRequest {
    @NotBlank private String password;
}
