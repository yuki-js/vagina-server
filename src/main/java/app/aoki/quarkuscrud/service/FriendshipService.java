package app.aoki.quarkuscrud.service;

import app.aoki.quarkuscrud.entity.Friendship;
import app.aoki.quarkuscrud.mapper.FriendshipMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing friendships between users.
 *
 * <p>This service handles friendship creation and retrieval. Friendships are mutual (bidirectional)
 * relationships between users.
 */
@ApplicationScoped
public class FriendshipService {

  @Inject FriendshipMapper friendshipMapper;
  @Inject ObjectMapper objectMapper;

  /**
   * Finds a friendship between two users regardless of direction.
   *
   * <p>This method searches for a friendship record between userId1 and userId2, checking both
   * possible orderings (userId1→userId2 or userId2→userId1).
   *
   * @param userId1 the first user ID
   * @param userId2 the second user ID
   * @return the friendship if found
   */
  public Optional<Friendship> findByParticipants(Long userId1, Long userId2) {
    return friendshipMapper.findByParticipants(userId1, userId2);
  }

  /**
   * Creates a new mutual friendship between sender and recipient. This creates two directional
   * relationships: sender->recipient and recipient->sender.
   *
   * @param senderId the sender user ID
   * @param recipientId the recipient user ID
   * @param meta optional metadata for the friendship
   * @return the created friendship from sender to recipient
   */
  @Transactional
  public Friendship createFriendship(Long senderId, Long recipientId, Map<String, Object> meta) {
    LocalDateTime now = LocalDateTime.now();
    String metaJson = serializeMeta(meta);

    // Create friendship from sender to recipient
    Friendship friendship = new Friendship();
    friendship.setSenderId(senderId);
    friendship.setRecipientId(recipientId);
    friendship.setUsermeta(metaJson);
    friendship.setSysmeta(null);
    friendship.setCreatedAt(now);
    friendship.setUpdatedAt(now);
    friendshipMapper.insert(friendship);

    // Create reverse friendship from recipient to sender
    Friendship reverseFriendship = new Friendship();
    reverseFriendship.setSenderId(recipientId);
    reverseFriendship.setRecipientId(senderId);
    reverseFriendship.setUsermeta(metaJson);
    reverseFriendship.setSysmeta(null);
    reverseFriendship.setCreatedAt(now);
    reverseFriendship.setUpdatedAt(now);
    friendshipMapper.insert(reverseFriendship);

    return friendship;
  }

  /**
   * Updates the meta field of a friendship.
   *
   * @param friendshipId the friendship ID to update
   * @param meta the new metadata
   */
  @Transactional
  public void updateMeta(Long friendshipId, Map<String, Object> meta) {
    Friendship friendship = friendshipMapper.findById(friendshipId).orElseThrow();
    friendship.setUsermeta(serializeMeta(meta));
    friendship.setSysmeta(null);
    friendship.setUpdatedAt(LocalDateTime.now());
    friendshipMapper.updateMeta(friendship);
  }

  private String serializeMeta(Map<String, Object> meta) {
    if (meta == null || meta.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(meta);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize meta", e);
    }
  }
}
