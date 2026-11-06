package cloud.treenewbee.quizforge.content.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

/** 领取、续租及提交结果均带代次；取消任务后旧工作线程不能提交。 */
@Mapper
public interface ImportQueueMapper {
    record Job(String id, String packId, int revision, String status, int attempts, Instant availableAt,
            String leaseOwner, Instant leaseUntil, long generation, String failureCode, Instant startedAt, Instant createdAt,
            Instant updatedAt) {}
    record Slot(int id, String jobId, Instant leaseUntil) {}

    @Insert("INSERT IGNORE INTO content_import_slot(id) VALUES(#{id})")
    int ensureSlot(@Param("id") int id);

    @Select("""
            SELECT id, job_id, lease_until FROM content_import_slot
             WHERE id < #{limit} AND (job_id IS NULL OR lease_until <= #{now})
             ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Optional<Slot> slot(@Param("limit") int limit, @Param("now") Instant now);

    @Select("""
            SELECT * FROM content_import_job WHERE status = 'queued' AND available_at <= #{now}
             ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Optional<Job> next(@Param("now") Instant now);

    @Insert("""
            INSERT INTO content_import_job(id, pack_id, revision, available_at, created_at, updated_at)
            VALUES(#{id}, #{packId}, #{revision}, #{now}, #{now}, #{now})
            """)
    int enqueue(@Param("id") String id, @Param("packId") String packId, @Param("revision") int revision,
            @Param("now") Instant now);

    @Update("""
            UPDATE content_import_job SET status='running', attempts=attempts+1, generation=generation+1,
                lease_owner=#{owner}, lease_until=#{until}, started_at=#{now}, updated_at=#{now}
             WHERE id=#{id} AND status='queued'
            """)
    int claim(@Param("id") String id, @Param("owner") String owner, @Param("until") Instant until,
            @Param("now") Instant now);

    @Update("UPDATE content_import_slot SET job_id=#{jobId}, generation=#{generation}, lease_until=#{until} WHERE id=#{slot}")
    int occupy(@Param("slot") int slot, @Param("jobId") String jobId, @Param("generation") long generation, @Param("until") Instant until);

    @Select("SELECT * FROM content_import_job WHERE id=#{id}")
    Optional<Job> find(@Param("id") String id);

    @Select("""
            SELECT * FROM content_import_job WHERE id=#{id} AND status='running'
             AND lease_owner=#{owner} AND generation=#{generation} AND lease_until > #{now} FOR UPDATE
            """)
    Optional<Job> fence(@Param("id") String id, @Param("owner") String owner,
            @Param("generation") long generation, @Param("now") Instant now);

    @Update("""
            UPDATE content_import_job SET lease_until=#{until}, updated_at=#{now}
             WHERE id=#{id} AND status='running' AND lease_owner=#{owner}
               AND generation=#{generation} AND lease_until > #{now}
            """)
    int renew(@Param("id") String id, @Param("owner") String owner, @Param("generation") long generation,
            @Param("until") Instant until, @Param("now") Instant now);

    @Update("UPDATE content_import_slot SET lease_until=#{until} WHERE job_id=#{id} AND generation=#{generation}")
    int renewSlot(@Param("id") String id, @Param("generation") long generation, @Param("until") Instant until);

    @Update("""
            UPDATE content_import_job SET status=#{status}, failure_code=#{code}, available_at=#{next},
                lease_until=NULL, lease_owner=NULL, updated_at=#{now}
             WHERE id=#{id} AND status='running' AND lease_owner=#{owner} AND generation=#{generation}
               AND lease_until > #{now}
            """)
    int finish(@Param("id") String id, @Param("owner") String owner, @Param("generation") long generation,
            @Param("status") String status, @Param("code") String code, @Param("next") Instant next,
            @Param("now") Instant now);

    @Update("UPDATE content_import_slot SET job_id=NULL, lease_until=NULL WHERE job_id=#{id} AND generation=#{generation}")
    int release(@Param("id") String id, @Param("generation") long generation);

    @Update("""
            UPDATE content_import_job SET status='queued',attempts=attempts-1,available_at=#{next},
                lease_until=NULL,lease_owner=NULL,updated_at=#{now}
             WHERE id=#{id} AND status='running' AND lease_owner=#{owner} AND generation=#{generation}
               AND lease_until > #{now}
            """)
    int yield(@Param("id") String id, @Param("owner") String owner, @Param("generation") long generation,
            @Param("next") Instant next, @Param("now") Instant now);

    @Update("""
            UPDATE content_import_job SET status='cancelled', generation=generation+1, lease_until=NULL,
                updated_at=#{now} WHERE pack_id=#{packId} AND status IN ('queued','running','failed')
            """)
    int cancel(@Param("packId") String packId, @Param("now") Instant now);

    // 先无锁查候选，再逐个按主键锁定；不持有租约索引的范围锁等待工作线程。
    @Select("""
            SELECT id FROM content_import_job WHERE status='running' AND lease_until <= #{now}
             ORDER BY lease_until,id LIMIT 20
            """)
    List<String> expired(@Param("now") Instant now);

    @Select("""
            SELECT id FROM content_import_job WHERE id=#{id} AND status='running' AND lease_until <= #{now}
             FOR UPDATE SKIP LOCKED
            """)
    Optional<String> lockExpired(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE content_import_job SET status=IF(attempts < 4,'queued','failed'), generation=generation+1,
                failure_code='WORKER_RESTARTED', available_at=TIMESTAMPADD(SECOND,5*attempts*attempts,#{now}),
                lease_owner=NULL,lease_until=NULL,updated_at=#{now}
             WHERE id=#{id} AND status='running' AND lease_until <= #{now}
            """)
    int recover(@Param("id") String id, @Param("now") Instant now);

    @Insert("""
            INSERT INTO content_upload_day(user_id,upload_day) VALUES(#{user},#{day})
            ON DUPLICATE KEY UPDATE uploads=content_upload_day.uploads
            """)
    int ensureDay(@Param("user") String user, @Param("day") LocalDate day);

    @Update("""
            UPDATE content_upload_day SET uploads=uploads+1
             WHERE user_id=#{user} AND upload_day=#{day} AND uploads < #{limit}
            """)
    int upload(@Param("user") String user, @Param("day") LocalDate day, @Param("limit") int limit);

    @Select("SELECT outcome FROM content_validation_cache WHERE extension_digest=#{digest} AND input_sha256=#{hash}")
    Optional<String> cached(@Param("digest") byte[] digest, @Param("hash") byte[] hash);

    record Cached(byte[] hash, String outcome) {}
    record CacheWrite(String hash, String outcome) {}

    @Select("""
            <script>
            SELECT input_sha256 AS hash,outcome FROM content_validation_cache WHERE extension_digest=#{digest}
              AND input_sha256 IN
              <foreach collection="hashes" item="hash" open="(" separator="," close=")">#{hash}</foreach>
            </script>
            """)
    List<Cached> cachedBatch(@Param("digest") byte[] digest, @Param("hashes") List<byte[]> hashes);

    @Insert("""
            <script>
            INSERT INTO content_validation_cache(extension_digest,input_sha256,outcome,created_at) VALUES
              <foreach collection="rows" item="row" separator=",">(#{digest},UNHEX(#{row.hash}),#{row.outcome},#{now})</foreach>
            ON DUPLICATE KEY UPDATE outcome=IF(JSON_CONTAINS_PATH(VALUES(outcome),'one','$.outlineItems'),VALUES(outcome),outcome)
            </script>
            """)
    int cacheBatch(@Param("digest") byte[] digest, @Param("rows") List<CacheWrite> rows, @Param("now") Instant now);

    @Insert("""
            INSERT INTO content_validation_cache(extension_digest,input_sha256,outcome,created_at)
            VALUES(#{digest},#{hash},#{outcome},#{now})
            ON DUPLICATE KEY UPDATE outcome=IF(JSON_CONTAINS_PATH(VALUES(outcome),'one','$.outlineItems'),VALUES(outcome),outcome)
            """)
    int cache(@Param("digest") byte[] digest, @Param("hash") byte[] hash,
            @Param("outcome") String outcome, @Param("now") Instant now);

    @Select("SELECT * FROM content_import_job WHERE status IN ('queued','running','failed') ORDER BY created_at,id LIMIT 200")
    List<Job> active();

    @Update("""
            UPDATE content_import_job j JOIN catalog_pack p ON p.id=j.pack_id
               SET j.status='queued',j.attempts=0,j.generation=j.generation+1,j.failure_code=NULL,
                   j.available_at=#{now},j.updated_at=#{now}
             WHERE j.id=#{id} AND j.status='failed' AND p.status='active' AND p.latest_revision=j.revision
            """)
    int retry(@Param("id") String id, @Param("now") Instant now);
}
