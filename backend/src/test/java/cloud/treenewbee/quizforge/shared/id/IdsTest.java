package cloud.treenewbee.quizforge.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class IdsTest {
    @Test
    void uuidV7HasVersionSevenAndRfcVariant() {
        long before = System.currentTimeMillis();
        String value = Ids.uuidV7();
        long after = System.currentTimeMillis();
        UUID uuid = UUID.fromString(value);
        assertThat(value).hasSize(36);
        assertThat(value.charAt(14)).isEqualTo('7');
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void sameMillisecondIdsAreUniqueAndSortInGenerationOrder() {
        long millis = 1_793_104_496_789L;
        Ids.Generator generator = new Ids.Generator(() -> millis);
        List<String> ids = IntStream.range(0, 10_000).mapToObj(i -> generator.next()).toList();
        assertThat(ids).doesNotHaveDuplicates().isSorted();
        assertThat(ids).allSatisfy(id -> {
            UUID uuid = UUID.fromString(id);
            assertThat(uuid.version()).isEqualTo(7);
            assertThat(uuid.variant()).isEqualTo(2);
            assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(millis);
        });
    }

    @Test
    void idsStayOrderedAcrossMillisecondsAndClockRollback() {
        AtomicLong clock = new AtomicLong(1_793_104_496_789L);
        Ids.Generator generator = new Ids.Generator(clock::get);
        List<String> ids = new ArrayList<>();
        ids.add(generator.next());
        clock.incrementAndGet();
        ids.add(generator.next());
        clock.addAndGet(-100);
        ids.add(generator.next());
        clock.addAndGet(200);
        ids.add(generator.next());
        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void publicUuidV7FactoryIsStrictlyIncreasing() {
        List<String> ids = IntStream.range(0, 10_000).mapToObj(i -> Ids.uuidV7()).toList();
        assertThat(ids).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void concurrentGenerationProducesUniqueIds() {
        Ids.Generator generator = new Ids.Generator(() -> 1_793_104_496_789L);
        List<String> ids = IntStream.range(0, 10_000).parallel().mapToObj(i -> generator.next()).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }
}
