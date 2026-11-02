package cloud.treenewbee.quizforge.library;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LibraryExportMapper {
    record Document(String bankId, String title, String groupName, String sourceTitle, String authorJson,
            String authorBindings, String description, String features, String listening) {}
    record Asset(String path, byte[] blobSha256) {}
    record Extension(byte[] digest, String manifest) {}
    record File(String path, byte[] blobSha256) {}
    record Question(String questionId, String title, byte[] extensionDigest, String outline, String listening,
            String data, Integer outlineGroupIndex, String outlineGroupName) {}

    @Select("""
            SELECT c.bank_id, c.title, c.group_name, b.source_title, b.author_json, b.author_bindings,
                   b.description, b.features, b.listening
            FROM practice_collection c JOIN content_bank b ON b.id = c.bank_id
            WHERE c.id = #{id} AND c.user_id = #{userId} AND c.deleted_at IS NULL
            """)
    Optional<Document> document(@Param("userId") String userId, @Param("id") String id);

    @Select("SELECT path, blob_sha256 FROM content_bank_asset WHERE bank_id = #{bankId} ORDER BY path")
    List<Asset> assets(@Param("bankId") String bankId);

    @Select("""
            SELECT question_id, title, extension_digest, outline, listening, data, outline_group_index, outline_group_name
            FROM content_question WHERE bank_id = #{bankId} ORDER BY position
            """)
    List<Question> questions(@Param("bankId") String bankId);

    @Select("SELECT digest, manifest FROM content_extension_package WHERE digest = #{digest}")
    Optional<Extension> extension(@Param("digest") byte[] digest);

    @Select("SELECT path, blob_sha256 FROM content_extension_file WHERE package_digest = #{digest} ORDER BY path")
    List<File> files(@Param("digest") byte[] digest);
}
