package cloud.treenewbee.quizforge.client.infrastructure;

import java.time.Instant;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 匿名下载的流量预算。先确保行存在（自动提交），之后事务内只做条件更新，不产生间隙锁。 */
@Mapper
public interface ClientTrafficMapper {

    @Insert("INSERT IGNORE INTO client_download_traffic (period, bytes, updated_at) VALUES (#{period}, 0, #{now})")
    int ensure(@Param("period") String period, @Param("now") Instant now);

    /** 预算足够时累加，返回 1；超出预算返回 0。 */
    @Update("""
            UPDATE client_download_traffic SET bytes = bytes + #{bytes}, updated_at = #{now}
             WHERE period = #{period} AND bytes + #{bytes} <= #{budget}
            """)
    int reserve(@Param("period") String period, @Param("bytes") long bytes, @Param("budget") long budget, @Param("now") Instant now);

    @Delete("DELETE FROM client_download_traffic WHERE updated_at < #{before} LIMIT 1000")
    int deleteOld(@Param("before") Instant before);
}
