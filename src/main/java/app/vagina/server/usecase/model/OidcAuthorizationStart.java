package app.vagina.server.usecase.model;

public record OidcAuthorizationStart(String authorizationUrl, String state, long expiresIn) {}
