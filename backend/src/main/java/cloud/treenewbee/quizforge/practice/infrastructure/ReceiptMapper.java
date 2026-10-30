package cloud.treenewbee.quizforge.practice.infrastructure;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReceiptMapper {


    // 查回执。要加 FOR SHARE，原因见下面的问题 ①
    // 不加 FOR SHARE：对不存在的行加锁读会持有间隙锁，并发 INSERT 回执时会死锁。
    // 漏读的情况由乐观锁兜底（409 → 在新事务里重查）。
    @Select("""
        SELECT request_sha256 AS requestSha256, response
        FROM practice_request_receipt
        WHERE user_id = #{userId} AND request_id = #{requestId}
        """)
    ReceiptRow find(@Param("userId") String userId, @Param("requestId") String requestId);

    // 写回执
    @Insert("""
        INSERT INTO practice_request_receipt (user_id, request_id, request_sha256, response, created_at)
        VALUES (#{userId}, #{requestId}, #{requestSha256}, #{response}, NOW(3))
        """)
    int insert(@Param("userId") String userId,
               @Param("requestId") String requestId,
               @Param("requestSha256") byte[] requestSha256,
               @Param("response") String response);

}
