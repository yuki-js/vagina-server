package app.vagina.server.service.model;

public record OidcUserInfo(
    String subject,
    String providerLogin,
    String displayName,
    String avatarUrl,
    String email,
    boolean emailVerified,
    String rawProfileJson) {}
