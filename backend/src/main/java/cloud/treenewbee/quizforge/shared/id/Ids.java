package cloud.treenewbee.quizforge.shared.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class Ids {

    private static final Generator GENERATOR = new Generator(System::currentTimeMillis);

    private Ids() {
    }

    /** UUIDv7 with a monotonic 74-bit random field (RFC 9562 section 6.2, method 2).
     * Ordering is guaranteed within this JVM, including same-millisecond calls and clock rollback.
     */
    public static String uuidV7() {
        return GENERATOR.next();
    }

    // Clock injection keeps same-millisecond and rollback tests deterministic.
    static final class Generator {
        private static final long RANDOM_MASK = 0x3fff_ffff_ffff_ffffL;
        private final SecureRandom random = new SecureRandom();
        private final LongSupplier clock;
        private long lastMillis = -1;
        private int randA;
        private long randB;

        Generator(LongSupplier clock) {
            this.clock = clock;
        }

        synchronized String next() {
            long now = clock.getAsLong();
            if (now > lastMillis) {
                lastMillis = now;
                randA = random.nextInt(1 << 12);
                randB = random.nextLong() & RANDOM_MASK;
            } else if (randB < RANDOM_MASK) {
                randB++;
            } else {
                randB = 0;
                if (++randA == (1 << 12)) {
                    randA = 0;
                    lastMillis++;
                }
            }
            return new UUID((lastMillis << 16) | 0x7000L | randA,
                    Long.MIN_VALUE | randB).toString();
        }
    }
}
