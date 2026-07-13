package app.vagina.server.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuthenticationEventMapper {

  @Insert(
      "INSERT INTO authentication_events "
          + "(user_id, token_family, event_type, ip_address, user_agent, created_at) VALUES "
          + "(#{userId}, #{tokenFamily}, #{eventType}, #{ipAddress}, #{userAgent}, #{createdAt})")
  void insert(
      @Param("userId") Long userId,
      @Param("tokenFamily") String tokenFamily,
      @Param("eventType") String eventType,
      @Param("ipAddress") String ipAddress,
      @Param("userAgent") String userAgent,
      @Param("createdAt") LocalDateTime createdAt);
}
