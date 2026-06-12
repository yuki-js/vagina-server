package app.vagina.server.usecase.model;

public record AuthSession(
    AuthUserView user, String accessToken, String refreshToken, long expiresIn) {}
