package cloud.treenewbee.quizforge.identity.infrastructure;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 登录与注册设置（auth_policy，单行）。 */
@Mapper
public interface PolicyMapper {

    record PolicyRow(boolean loginCode, boolean loginCaptcha, boolean registrationCode,
                     boolean registrationCaptcha, boolean invitationRequired, long revision) {
    }

    @Select("""
            SELECT login_code, login_captcha, registration_code, registration_captcha, invitation_required, revision
              FROM auth_policy WHERE id = 1
            """)
    PolicyRow get();

    @Select("""
            SELECT login_code, login_captcha, registration_code, registration_captcha, invitation_required, revision
              FROM auth_policy WHERE id = 1 FOR UPDATE
            """)
    PolicyRow lock();

    @Update("""
            UPDATE auth_policy SET login_code = #{p.loginCode}, login_captcha = #{p.loginCaptcha},
                   registration_code = #{p.registrationCode}, registration_captcha = #{p.registrationCaptcha},
                   invitation_required = #{p.invitationRequired}, revision = revision + 1, updated_at = #{now}
             WHERE id = 1 AND revision = #{revision}
            """)
    int update(@Param("p") PolicyRow next, @Param("revision") long revision, @Param("now") Instant now);
}
