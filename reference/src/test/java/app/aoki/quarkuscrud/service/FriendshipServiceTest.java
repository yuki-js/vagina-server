package app.aoki.quarkuscrud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.aoki.quarkuscrud.entity.Friendship;
import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.mapper.FriendshipMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Unit tests for FriendshipService.
 *
 * <p>Tests friendship creation, retrieval, and relationship management.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FriendshipServiceTest {

  @Inject FriendshipService friendshipService;
  @Inject FriendshipMapper friendshipMapper;
  @Inject UserService userService;

  private static Long testUser1Id;
  private static Long testUser2Id;
  private static Long testUser3Id;

  @Test
  @Order(1)
  @Transactional
  public void setup() {
    // Create test users
    User user1 = userService.createAnonymousUser();
    User user2 = userService.createAnonymousUser();
    User user3 = userService.createAnonymousUser();

    testUser1Id = user1.getId();
    testUser2Id = user2.getId();
    testUser3Id = user3.getId();
  }

  @Test
  @Order(2)
  @Transactional
  public void testCreateFriendship() {
    Friendship friendship = friendshipService.createFriendship(testUser1Id, testUser2Id, null);

    assertNotNull(friendship);
    assertNotNull(friendship.getId());
    assertEquals(testUser1Id, friendship.getSenderId());
    assertEquals(testUser2Id, friendship.getRecipientId());
    assertNotNull(friendship.getCreatedAt());
    assertNotNull(friendship.getUpdatedAt());
  }

  @Test
  @Order(3)
  public void testFindBySenderAndRecipient() {
    Optional<Friendship> friendship =
        friendshipMapper.findBySenderAndRecipient(testUser1Id, testUser2Id);

    assertTrue(friendship.isPresent());
    assertEquals(testUser1Id, friendship.get().getSenderId());
    assertEquals(testUser2Id, friendship.get().getRecipientId());
  }

  @Test
  @Order(4)
  public void testFindBySenderAndRecipientReverse() {
    // With mutual friendships, the reverse direction should also exist
    Optional<Friendship> friendship =
        friendshipMapper.findBySenderAndRecipient(testUser2Id, testUser1Id);
    assertTrue(friendship.isPresent());
    assertEquals(testUser2Id, friendship.get().getSenderId());
    assertEquals(testUser1Id, friendship.get().getRecipientId());
  }

  @Test
  @Order(5)
  public void testFindByRecipientId() {
    List<Friendship> friendships = friendshipMapper.findByRecipientId(testUser2Id);

    assertFalse(friendships.isEmpty());
    assertTrue(friendships.stream().anyMatch(f -> f.getSenderId().equals(testUser1Id)));
  }

  @Test
  @Order(6)
  public void testFindByRecipientIdMutual() {
    // With mutual friendships, user1 should also have received friendships
    List<Friendship> friendships = friendshipMapper.findByRecipientId(testUser1Id);
    assertFalse(friendships.isEmpty());
    assertTrue(friendships.stream().anyMatch(f -> f.getSenderId().equals(testUser2Id)));
  }

  @Test
  @Order(7)
  @Transactional
  public void testCreateMultipleFriendships() {
    // Create friendships from user1 to user3
    Friendship friendship1 = friendshipService.createFriendship(testUser1Id, testUser3Id, null);
    assertNotNull(friendship1);

    // Create friendships from user2 to user3
    Friendship friendship2 = friendshipService.createFriendship(testUser2Id, testUser3Id, null);
    assertNotNull(friendship2);

    // Verify user3 has two friendships
    List<Friendship> friendships = friendshipMapper.findByRecipientId(testUser3Id);
    assertEquals(2, friendships.size());
  }

  @Test
  @Order(8)
  @Transactional
  public void testCreateMutualFriendshipAutomatically() {
    Long user4Id = userService.createAnonymousUser().getId();
    Long user5Id = userService.createAnonymousUser().getId();

    // Create friendship from user4 to user5
    Friendship friendship1 = friendshipService.createFriendship(user4Id, user5Id, null);
    assertNotNull(friendship1);

    // Verify both directions exist automatically
    assertTrue(friendshipMapper.findBySenderAndRecipient(user4Id, user5Id).isPresent());
    assertTrue(friendshipMapper.findBySenderAndRecipient(user5Id, user4Id).isPresent());
  }
}
