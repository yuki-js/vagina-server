package app.vagina.server.service.model;

public record OidcTokenSet(String accessToken, String idToken, long expiresIn) {}
