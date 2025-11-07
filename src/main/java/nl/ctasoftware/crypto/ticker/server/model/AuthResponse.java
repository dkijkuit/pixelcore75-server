package nl.ctasoftware.crypto.ticker.server.model;

public record AuthResponse(String accessToken, String refreshToken, Px75User px75User) {}