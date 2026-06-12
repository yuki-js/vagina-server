package app.vagina.server.usecase.model;

import app.vagina.server.entity.AccountLifecycle;
import java.time.LocalDateTime;

public record AuthUserView(
    Long id,
    AccountLifecycle accountLifecycle,
    String displayName,
    String avatarUrl,
    LocalDateTime createdAt) {}
