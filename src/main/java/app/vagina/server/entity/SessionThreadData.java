package app.vagina.server.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public final class SessionThreadData {
  private String id;
  private String conversationId;
  private List<Map<String, Object>> items;

  public SessionThreadData() {}

  public SessionThreadData(String id, String conversationId, List<Map<String, Object>> items) {
    this.id = id;
    this.conversationId = conversationId;
    this.items = items == null ? List.of() : List.copyOf(items);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getConversationId() {
    return conversationId;
  }

  public void setConversationId(String conversationId) {
    this.conversationId = conversationId;
  }

  public List<Map<String, Object>> getItems() {
    return items == null ? List.of() : items;
  }

  public void setItems(List<Map<String, Object>> items) {
    this.items = items == null ? List.of() : List.copyOf(items);
  }
}
