package cloud.treenewbee.quizforge.identity.infrastructure;

import java.util.Optional;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO account_user
                (id, email, name, password_hash, password_algo, is_admin, created_at, updated_at, version)
            VALUES (#{id}, #{email}, #{name}, #{passwordHash}, #{passwordAlgo}, #{isAdmin},
                    #{createdAt}, #{updatedAt}, #{version})
            """)
    int insert(UserRow user);

    @Select("""
            SELECT id, email, name, password_hash, password_algo, is_admin, created_at, updated_at, version
              FROM account_user WHERE id = #{id}
            """)
    Optional<UserRow> findById(@Param("id") String id);

    // The first predicate uses uk_user_email; the second enforces exact bytes despite ai_ci collation.
    @Select("""
            SELECT id, email, name, password_hash, password_algo, is_admin, created_at, updated_at, version
              FROM account_user
             WHERE email = #{email} AND BINARY email = BINARY #{email}
            """)
    Optional<UserRow> findByEmail(@Param("email") String email);
}
