package app.vagina.server.usecase.model;

import app.vagina.server.entity.ClientType;

public record OidcLoginStartRequest(
    ClientType clientType, String redirectUri, String codeChallenge, String codeChallengeMethod) {}
