package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BankAssetMapper {
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
