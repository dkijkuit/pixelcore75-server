package nl.ctasoftware.crypto.ticker.server.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;

import java.util.Set;

@Data
public class CreateUserRequest {
    @NotBlank private String username;
    @NotBlank private String password;
    @NotBlank @Email private String email;
    private Set<Px75Role> roles; // optional; defaults to USER if empty
}

