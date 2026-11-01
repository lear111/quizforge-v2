package cloud.treenewbee.quizforge.practice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import cloud.treenewbee.quizforge.content.application.BankAvailability;
import cloud.treenewbee.quizforge.content.application.ContentPages;
import cloud.treenewbee.quizforge.content.infrastructure.QuestionMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadMapper;
import cloud.treenewbee.quizforge.practice.infrastructure.PracticeReadRows;
import cloud.treenewbee.quizforge.practice.projection.ProjectionCache;
import cloud.treenewbee.quizforge.rules.RuleRunnerClient;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class PracticeCollectionSnapshotTest {
    static final String USER = "owner", COLLECTION = "collection", BANK = "bank", CONTEXT = "context";
    static final byte[] DIGEST = HexFormat.of().parseHex("aa".repeat(32));
    static final Instant NOW = Instant.parse("2026-01-02T03:04:05.678Z");
    final JsonMapper json = JsonMapper.builder().build();
    final PracticeReadMapper mapper = mock(PracticeReadMapper.class);
    final QuestionMapper questions = mock(QuestionMapper.class);
    final BankAvailability availability = mock(BankAvailability.class);
    final ContentPages pages = mock(ContentPages.class);
    final RuleRunnerClient runner = mock(RuleRunnerClient.class);
    final ProjectionCache cache = mock(ProjectionCache.class);
    final QuestionStamps stamps = mock(QuestionStamps.class);
    final PracticeReadService reads = new PracticeReadService(mapper, questions, availability, pages, runner, cache,
            stamps, Clock.fixed(NOW, ZoneOffset.UTC), json, mock(cloud.treenewbee.quizforge.practice.whiteboard.WhiteboardViews.class));

    @BeforeEach void fixture() {
        when(mapper.collection(USER, COLLECTION)).thenReturn(Optional.of(new PracticeReadRows.Collection(
                COLLECTION, BANK, "标题\"中文😀", "题库分组", null, "{\"draft\":true}",
                "{\"materials\":[{\"id\":\"intro\",\"title\":\"听力材料\",\"file\":\"assets/listening.mp3\"}],\"examMaterialId\":\"intro\"}", DIGEST, 3)));
        when(availability.isAvailable(BANK)).thenReturn(true);
        when(mapper.context(USER, COLLECTION)).thenReturn(Optional.of(CONTEXT));
        when(stamps.stamp(anyString(), anyString(), anyLong(), any(), any()))
                .thenReturn(new QuestionStamps.Stamp(0, "listening-version", "package-version"));
        when(mapper.questions(BANK, CONTEXT, USER)).thenReturn(List.of(
                summary("q-z", "第一题", "{\"level\":\"question\",\"label\":\"1\"}", "{\"materialId\":\"intro\"}", 0, "同名", "11"),
                summary("q-a", "第二题", "{\"level\":\"parts\",\"items\":[{\"id\":\"p1\",\"label\":\"(1)\"}]}", null, 1, "同名", "22"),
                summary("q-none", "第三题", null, null, 2, null, "33")));
    }

    @Test void snapshotMatchesExistingFullJsonContract() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/practice/collection-snapshot.json")) {
            assertThat(fixture).isNotNull();
            assertThat(json.readTree(reads.collectionSnapshot(USER, COLLECTION)))
                    .isEqualTo(json.readTree(new String(fixture.readAllBytes(), StandardCharsets.UTF_8)));
        }
        verify(mapper).collection(USER, COLLECTION);
        verify(availability).isAvailable(BANK);
        verify(mapper).questions(BANK, CONTEXT, USER);
    }

    @ParameterizedTest @CsvSource({"owner,missing", "owner,another-users-collection", "outsider,collection"})
    void snapshotUsesReadOwnershipCheckBeforeLoadingQuestions(String user, String collection) {
        assertThatThrownBy(() -> reads.collectionSnapshot(user, collection))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("NOT_FOUND");
                });
        verify(mapper).collection(user, collection);
        verifyNoInteractions(availability);
        verify(mapper, never()).context(anyString(), anyString());
        verify(mapper, never()).questions(anyString(), anyString(), anyString());
    }

    @Test void snapshotRejectsUnavailableBankBeforeLoadingQuestions() {
        when(availability.isAvailable(BANK)).thenReturn(false);
        assertThatThrownBy(() -> reads.collectionSnapshot(USER, COLLECTION))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(error.code()).isEqualTo("CLOUD_EXTENSION_REVIEW_REQUIRED");
                });
        verify(mapper, never()).context(anyString(), anyString());
        verify(mapper, never()).questions(anyString(), anyString(), anyString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void snapshotUsesExistingContextCreationAndConcurrentDeduplication(boolean concurrent) {
        when(mapper.context(USER, COLLECTION)).thenReturn(Optional.empty(), Optional.of(CONTEXT));
        if (concurrent) doThrow(new DuplicateKeyException("uk_context_scope"))
                .when(mapper).insertContext(anyString(), eq(USER), eq(COLLECTION), eq(NOW));
        assertThat(json.readTree(reads.collectionSnapshot(USER, COLLECTION)).path("questions").size()).isEqualTo(3);
        verify(mapper).insertContext(anyString(), eq(USER), eq(COLLECTION), eq(NOW));
        verify(mapper, times(2)).context(USER, COLLECTION);
        verify(mapper).questions(BANK, CONTEXT, USER);
    }

    @Test void snapshotDoesNotProjectOrReadRawQuestionContent() {
        String snapshot = reads.collectionSnapshot(USER, COLLECTION);
        verifyNoInteractions(questions, pages, runner, cache);
        assertThat(snapshot).doesNotContain("private-state", "private-answer", "private-result", "submitted");
        for (var question : json.readTree(snapshot).path("questions")) {
            for (String field : List.of("data", "answer", "result", "state", "revision")) {
                assertThat(question.has(field)).as(field).isFalse();
            }
        }
    }

    private static PracticeReadRows.Summary summary(String id, String title, String outline, String listening,
            Integer groupIndex, String groupName, String hashByte) {
        // Joined answer/result state must never enter a collection snapshot.
        return new PracticeReadRows.Summary(id, title, outline, listening, DIGEST, "basic", "1.0", "基础题型",
                "submitted", "{\"private-state\":true}", "{\"private-answer\":true}", "{\"private-result\":true}",
                9L, groupIndex, groupName, HexFormat.of().parseHex(hashByte.repeat(32)), null, null, "graded");
    }
}
