package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BankAssetMapper {
    /** The caller supplies a nonempty batch; duplicate keys fail the whole statement. */
    @Insert("""
            <script>
            INSERT INTO content_bank_asset (bank_id, path, blob_sha256) VALUES
            <foreach collection="rows" item="row" separator=",">
              (#{row.bankId}, #{row.path}, #{row.blobSha256})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<BankAssetRow> rows);

    @Insert("""
            INSERT INTO content_bank_asset (bank_id, path, blob_sha256)
            VALUES (#{bankId}, #{path}, #{blobSha256})
            """)
    int insert(BankAssetRow row);

    @Select("""
            SELECT bank_id, path, blob_sha256
              FROM content_bank_asset WHERE bank_id = #{bankId} AND path = #{path} AND BINARY path = BINARY #{path}
            """)
    Optional<BankAssetRow> findByPath(@Param("bankId") String bankId, @Param("path") String path);
}
