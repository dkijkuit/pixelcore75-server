package nl.ctasoftware.crypto.ticker.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CreateUserRequest;
import nl.ctasoftware.crypto.ticker.server.model.dto.UpdatePasswordRequest;
import nl.ctasoftware.crypto.ticker.server.model.dto.UpdateUserRequest;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/v1/user")
@RequiredArgsConstructor
public class UserController {
    final Px75UserDetailsService userDetailsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<Px75User> getUsers() {
        return userDetailsService.getUsers();
    }

    @GetMapping("/{userId}")
    ResponseEntity<Px75User> getUserById(@AuthenticationPrincipal Px75User user, @PathVariable long userId) {
        if(user.getRoles().contains(Px75Role.ADMIN) || user.getId() == userId) {
            return ResponseEntity.ok(userDetailsService.getPx75UserById(userId));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping("/by-username/{username}")
    @PreAuthorize("hasRole('ADMIN')")
    Px75User getUserByUsername(@PathVariable String username) {
        return userDetailsService.getPx75User(username);
    }

    @GetMapping("/by-email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    Px75User getUserByEmail(@PathVariable String email) {
        return userDetailsService.getPx75UserByEmail(email);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<Px75User> createUser(@Valid @RequestBody CreateUserRequest body) {
        final Px75User toCreate = new Px75User();
        toCreate.setUsername(body.getUsername());
        toCreate.setPassword(body.getPassword());
        toCreate.setEmail(body.getEmail());
        if (body.getRoles() != null) toCreate.setRoles(body.getRoles());

        Px75User created = userDetailsService.addUser(toCreate);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{userId}")
    ResponseEntity<Px75User> updateUser(@AuthenticationPrincipal Px75User user, @PathVariable long userId, @Valid @RequestBody UpdateUserRequest body) {
        if(user.getRoles().contains(Px75Role.ADMIN) || user.getId() == userId) {
            Px75User changes = new Px75User();
            if (StringUtils.hasText(body.getUsername())) changes.setUsername(body.getUsername());
            if (StringUtils.hasText(body.getPassword())) changes.setPassword(body.getPassword()); // will be encoded
            if (StringUtils.hasText(body.getEmail())) changes.setEmail(body.getEmail());
            if (user.getRoles().contains(Px75Role.ADMIN)) {
                if (body.getRoles() != null) changes.setRoles(body.getRoles());
            }
            return ResponseEntity.ok(userDetailsService.updateUser(userId, changes));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

    }

    @PatchMapping("/{userId}/password")
    @PreAuthorize("hasRole('ADMIN')")
    Px75User updatePassword(@PathVariable long userId, @Valid @RequestBody UpdatePasswordRequest body) {
        return userDetailsService.updatePassword(userId, body.getPassword());
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteUser(@PathVariable long userId) {
        userDetailsService.deleteUser(userId);
    }
}
