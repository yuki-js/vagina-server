package app.aoki.quarkuscrud.usecase;

import app.aoki.quarkuscrud.entity.Friendship;
import app.aoki.quarkuscrud.mapper.FriendshipMapper;
import app.aoki.quarkuscrud.service.FriendshipService;
import app.aoki.quarkuscrud.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Use case for friendship-related business flows.
 *
 * <p>This use case orchestrates complete business operations including validation, business logic,
 * and DTO mapping.
 */
@ApplicationScoped
public class FriendshipUseCase {

  @Inject FriendshipService friendshipService;
  @Inject UserService userService;
  @Inject FriendshipMapper friendshipMapper;
  @Inject ProfileUseCase profileUseCase;
  @Inject ObjectMapper objectMapper;

  /**
   * Gets a friendship between the authenticated user and another user.
   *
   * <p>This method searches for a friendship record between the current user and the specified
   * other user, checking both possible orderings (currentUser→otherUser or otherUser→currentUser).
   *
   * @param currentUserId the ID of the currently authenticated user
   * @param otherUserId the ID of the other user
   * @return the friendship as DTO
   * @throws IllegalArgumentException if no friendship exists between the users
   */
  public app.aoki.quarkuscrud.generated.model.Friendship getFriendshipByOtherUser(
      Long currentUserId, Long otherUserId) {
    Friendship friendship =
        friendshipService
            .findByParticipants(currentUserId, otherUserId)
            .orElseThrow(() -> new IllegalArgumentException("Friendship not found"));

    return toFriendshipDto(friendship);
  }

  /**
   * Lists received friendships for a user.
   *
   * @param userId the recipient user ID
   * @return list of friendship DTOs
   */
  public List<app.aoki.quarkuscrud.generated.model.Friendship> listReceivedFriendships(
      Long userId) {
    return friendshipMapper.findByRecipientId(userId).stream()
        .map(this::toFriendshipDto)
        .collect(Collectors.toList());
  }

  /**
   * Creates a mutual friendship between sender and recipient, or updates meta if it already exists.
   * This operation is idempotent - if the friendship already exists, it updates the meta and
   * returns the existing friendship instead of throwing an error.
   *
   * @param senderId the sender user ID
   * @param recipientId the recipient user ID
   * @param meta optional metadata for the friendship
   * @return the created or updated friendship as DTO
   * @throws IllegalArgumentException if recipient user not found
   */
  @Transactional
  public app.aoki.quarkuscrud.generated.model.Friendship createOrUpdateFriendship(
      Long senderId, Long recipientId, java.util.Map<String, Object> meta) {
    if (userService.findById(recipientId).isEmpty()) {
      throw new IllegalArgumentException("User not found");
    }

    // Check if friendship already exists in either direction
    Optional<Friendship> existingFriendship =
        friendshipService.findByParticipants(senderId, recipientId);

    if (existingFriendship.isPresent()) {
      // Friendship exists - update meta if provided
      Friendship friendship = existingFriendship.get();
      if (meta != null && !meta.isEmpty()) {
        // Update both directions
        friendshipService.updateMeta(friendship.getId(), meta);

        // Find and update the reverse friendship
        Optional<Friendship> reverseFriendship =
            friendshipMapper.findBySenderAndRecipient(
                friendship.getRecipientId(), friendship.getSenderId());
        reverseFriendship.ifPresent(rf -> friendshipService.updateMeta(rf.getId(), meta));

        // Reload the updated friendship
        friendship = friendshipMapper.findById(friendship.getId()).orElseThrow();
      }
      return toFriendshipDto(friendship);
    }

    // Create new friendship
    Friendship friendship = friendshipService.createFriendship(senderId, recipientId, meta);
    return toFriendshipDto(friendship);
  }

  private app.aoki.quarkuscrud.generated.model.Friendship toFriendshipDto(Friendship friendship) {
    app.aoki.quarkuscrud.generated.model.Friendship response =
        new app.aoki.quarkuscrud.generated.model.Friendship();
    response.setId(friendship.getId());
    response.setSenderUserId(friendship.getSenderId());
    response.setRecipientUserId(friendship.getRecipientId());
    response.setCreatedAt(friendship.getCreatedAt().atOffset(ZoneOffset.UTC));
    if (friendship.getUpdatedAt() != null) {
      response.setUpdatedAt(friendship.getUpdatedAt().atOffset(ZoneOffset.UTC));
    }
    if (friendship.getUsermeta() != null && !friendship.getUsermeta().isEmpty()) {
      response.setMeta(deserializeMeta(friendship.getUsermeta()));
    }

    // Populate senderProfile if available
    profileUseCase.getLatestProfile(friendship.getSenderId()).ifPresent(response::setSenderProfile);

    return response;
  }

  private Map<String, Object> deserializeMeta(String metaJson) {
    if (metaJson == null || metaJson.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      // If deserialization fails, return null
      return null;
    }
  }
}
