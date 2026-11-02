package cloud.treenewbee.quizforge.content.infrastructure;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 从已存储的内容重新打包 .qbank 时读取的数据（只读）。 */
@Mapper
public interface ExportMapper {

    record ExportBank(String id, String bankUuid, byte[] bankJsonSha256, String sourceTitle) {
    }

    record ExportQuestion(String questionId, byte[] extensionDigest) {
    }

    record ExportFile(String path, byte[] blobSha256) {
    }

    @Select("SELECT id, bank_uuid, bank_json_sha256, source_title FROM content_bank WHERE id = #{id}")
    ExportBank bank(@Param("id") String id);

    @Select("SELECT question_id, extension_digest FROM content_question WHERE bank_id = #{bankId} ORDER BY position")
    List<ExportQuestion> questions(@Param("bankId") String bankId);

    @Select("SELECT path, blob_sha256 FROM content_bank_asset WHERE bank_id = #{bankId} ORDER BY path")
    List<ExportFile> assets(@Param("bankId") String bankId);

    @Select("SELECT path, blob_sha256 FROM content_extension_file WHERE package_digest = #{digest} ORDER BY path")
    List<ExportFile> extensionFiles(@Param("digest") byte[] digest);
}
