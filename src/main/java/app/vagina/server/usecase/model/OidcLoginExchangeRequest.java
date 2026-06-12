package app.vagina.server.usecase.model;

public record OidcLoginExchangeRequest(
    String code, String state, String redirectUri, String codeVerifier) {}
