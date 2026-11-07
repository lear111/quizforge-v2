package cloud.treenewbee.quizforge.client.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.treenewbee.quizforge.catalog.infrastructure.CatalogMapper;
import cloud.treenewbee.quizforge.catalog.infrastructure.PlazaMapper;
import cloud.treenewbee.quizforge.client.infrastructure.ClientTrafficMapper;

/**
 * 固定版本导入（遗留契约 C-05 第 10–12 节）。题库包 ID 就是小写 UUID，版本号是已审核版本的正整数，都与旧客户端期望的格式一致。
 * 只提供公开、已审核的版本；版本或 SHA-256 不符时不自动换成其他版本。
 */
@Service
public class LocalImportService {

    /** 固定版本导入的错误：响应格式与其他接口不同（{"error":"文字","code":"LOCAL_IMPORT_*"}）。 */
    public static class Failure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        Failure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = "LOCAL_IMPORT_" + code;
        }

        public HttpStatus status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** 清单与下载所需的内容。 */
    public record Archive(String packId, long revision, String title, String name, long size, String sha256) {
    }

    private static final Pattern PACK = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern REVISION = Pattern.compile("[1-9][0-9]{0,15}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern QUERY = Pattern.compile("[a-z0-9=&-]+");
    private static final long MAX_REVISION = 9007199254740991L;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration KEEP_TRAFFIC = Duration.ofDays(40);
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);

    private final PlazaMapper plaza;
    private final CatalogMapper catalog;
    private final ClientTrafficMapper traffic;
    private final ClientRateLimiter limits;
    private final ClientSettings settings;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private volatile Instant nextCleanup = Instant.EPOCH;

    public LocalImportService(PlazaMapper plaza, CatalogMapper catalog, ClientTrafficMapper traffic, ClientRateLimiter limits,
                              ClientSettings settings, TransactionTemplate transactions, Clock clock) {
        this.plaza = plaza;
        this.catalog = catalog;
        this.traffic = traffic;
        this.limits = limits;
        this.settings = settings;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** 清单：不扣流量。 */
    public Archive manifest(String id, String method, Map<String, String[]> params, String query, String ip) {
        return checked(id, method, params, query, ip, false);
    }

    /** 下载前的检查：预留流量并增加下载数。文件由 Nginx 传输。 */
    public Archive download(String id, String method, Map<String, String[]> params, String query, String ip) {
        Archive archive = checked(id, method, params, query, ip, true);
        reserve(archive, ip);
        return archive;
    }

    private Archive checked(String id, String method, Map<String, String[]> params, String query, String ip, boolean download) {
        if (!settings.localImportEnabled()) throw new Failure(HttpStatus.NOT_FOUND, "DISABLED", "Hub 暂未开启直接导入，请下载 .qbank 文件");
        if (!"GET".equals(method)) throw new Failure(HttpStatus.METHOD_NOT_ALLOWED, "REQUEST_INVALID", "此接口只支持 GET");
        limits.check(download ? "download" : "local-import-manifest", ip, download ? 30 : 60,
                (status, message) -> new Failure(status, "RATE_LIMITED", message));
        Set<String> allowed = download ? Set.of("revision", "sha256") : Set.of("revision");
        String revisionText = single(params, "revision");
        String sha = single(params, "sha256");
        if (!PACK.matcher(id).matches() || !params.keySet().equals(allowed)
                || params.values().stream().anyMatch(values -> values.length != 1)
                || query == null || !QUERY.matcher(query).matches() || revisionText == null || !REVISION.matcher(revisionText).matches()
                || download && (sha == null || !SHA256.matcher(sha).matches())) {
            throw invalid();
        }
        long revision = Long.parseLong(revisionText);
        if (revision > MAX_REVISION) throw invalid();
        return current(id, revision, sha);
    }

    private static String single(Map<String, String[]> params, String name) {
        String[] values = params.get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static Failure invalid() {
        return new Failure(HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Hub 导入参数无效");
    }

    private Archive current(String id, long revision, String sha) {
        var pack = plaza.visible(id).orElseThrow(LocalImportService::unavailable);
        var row = catalog.revision(id, pack.revision()).orElseThrow(LocalImportService::unavailable);
        if (row.blobSha256() == null) throw unavailable();
        String actual = HexFormat.of().formatHex(row.blobSha256());
        if (pack.revision() != revision || sha != null && !sha.equals(actual)) {
            throw new Failure(HttpStatus.CONFLICT, "REVISION_CHANGED", "题库版本已变化，请刷新 Hub 后重新点击");
        }
        if (row.filename() == null || !row.filename().toLowerCase(Locale.ROOT).endsWith(".qbank")) {
            throw new Failure(HttpStatus.UNPROCESSABLE_CONTENT, "FORMAT_UNSUPPORTED", "此附件不支持直接导入，请使用文件下载");
        }
        if (row.size() > MAX_BYTES) throw new Failure(HttpStatus.CONTENT_TOO_LARGE, "TOO_LARGE", "题库文件超过 64 MiB");
        if (row.size() < 1) throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_ERROR", "题库附件信息无效");
        return new Archive(id, revision, pack.title(), row.filename(), row.size(), actual);
    }

    private static Failure unavailable() {
        return new Failure(HttpStatus.NOT_FOUND, "UNAVAILABLE", "题库不存在、尚未公开或附件暂不可用");
    }

    private void reserve(Archive archive, String ip) {
        Instant now = clock.instant();
        var today = now.atZone(ZONE).toLocalDate();
        String month = "month:" + today.toString().substring(0, 7);
        String day = "day:" + today + ":" + digest(ip);
        traffic.ensure(month, now);
        traffic.ensure(day, now);
        transactions.executeWithoutResult(status -> {
            // 固定顺序（站点 → IP）；任一超出都回滚，不计入流量和下载数。
            if (traffic.reserve(month, archive.size(), settings.monthlyBudgetBytes(), now) != 1) {
                throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "TRAFFIC_LIMIT", "本月附件下载预算已用完，请下月再来或联系管理员");
            }
            if (traffic.reserve(day, archive.size(), settings.ipDailyBudgetBytes(), now) != 1) {
                throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "TRAFFIC_LIMIT", "今天的附件下载额度已用完，请明天再来");
            }
            // 条件中再次确认版本仍然公开且未变化。
            if (plaza.download(archive.packId(), (int) archive.revision()) != 1) {
                throw new Failure(HttpStatus.CONFLICT, "REVISION_CHANGED", "题库文件已变化，请刷新 Hub 后重新点击");
            }
            plaza.recordDownload(today, archive.packId());
        });
        if (nextCleanup.isBefore(now)) {
            nextCleanup = now.plus(CLEANUP_INTERVAL);
            traffic.deleteOld(now.minus(KEEP_TRAFFIC));
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
