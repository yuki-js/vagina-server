package app.aoki.quarkuscrud.service;

/**
 * Enum representing similarity levels for name generation.
 *
 * <p>This enum defines six levels of similarity from almost identical to completely different, with
 * Japanese descriptions for each level.
 */
public enum SimilarityLevel {
  ALMOST_SAME("ほぼ違いがない名前"),
  VERY_SIMILAR("とても良く似ている名前"),
  MODERATELY_SIMILAR("結構似ている名前"),
  SOMEWHAT_SIMILAR("多少似ているといえるくらいの名前"),
  FAINTLY_SIMILAR("かすかに類似性を感じられる名前"),
  COMPLETELY_DIFFERENT("互いにまったく似ていない名前");

  private final String value;

  SimilarityLevel(String value) {
    this.value = value;
  }

  /**
   * Gets the Japanese description for this similarity level.
   *
   * @return the Japanese description
   */
  public String getValue() {
    return value;
  }

  /**
   * Finds a SimilarityLevel by its Japanese description value.
   *
   * @param value the Japanese description to look up
   * @return the matching SimilarityLevel
   * @throws IllegalArgumentException if no matching level is found
   */
  public static SimilarityLevel fromValue(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Similarity level value cannot be null");
    }

    for (SimilarityLevel level : values()) {
      if (level.value.equals(value)) {
        return level;
      }
    }

    throw new IllegalArgumentException("Unknown similarity level: " + value);
  }
}
