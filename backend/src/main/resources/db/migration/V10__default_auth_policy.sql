-- 上线默认的登录与注册设置（作者决定，2026-11-01，PR #88 D-1）：
-- 注册需要邮箱 / 短信验证码；注册人机验证、登录验证码、登录人机验证、邀请码默认关闭。
-- 只修改从未被管理员保存过的初始行（revision = 0），已有的设置保持不变。
UPDATE auth_policy
   SET login_code = FALSE,
       login_captcha = FALSE,
       registration_code = TRUE,
       registration_captcha = FALSE,
       invitation_required = FALSE
 WHERE id = 1 AND revision = 0;
