package cloud.treenewbee.quizforge.content.web;

import cloud.treenewbee.quizforge.content.application.ImportUploadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import cloud.treenewbee.quizforge.shared.web.ApiException;

/** multipart 延迟解析开启时，在读取 archive/表单参数之前检查身份、请求总长度与每日次数。 */
@Configuration
public class UploadPreflightConfig implements WebMvcConfigurer {
    private final ImportUploadService uploads;

    public UploadPreflightConfig(ImportUploadService uploads) { this.uploads = uploads; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!"POST".equals(request.getMethod())) return true;
                String path = request.getRequestURI();
                if (!path.equals("/api/catalog/packs") && !path.matches("/api/catalog/packs/[^/]+/revisions")) return true;
                if (request.getContentLengthLong() > 150L * 1024 * 1024) {
                    throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "LIBRARY_ARCHIVE_LIMIT", "上传请求超过大小上限");
                }
                uploads.preflight(request.getUserPrincipal().getName(), -1, false);
                return true;
            }
        }).addPathPatterns("/api/catalog/packs", "/api/catalog/packs/*/revisions");
    }
}
