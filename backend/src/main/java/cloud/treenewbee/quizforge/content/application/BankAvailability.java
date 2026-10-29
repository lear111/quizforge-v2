package cloud.treenewbee.quizforge.content.application;

import cloud.treenewbee.quizforge.content.infrastructure.BankMapper;
import org.springframework.stereotype.Service;

/** Approval is read live: revoking one extension affects the next availability query. */
@Service
public class BankAvailability {
    private final BankMapper banks;
    public BankAvailability(BankMapper banks) { this.banks = banks; }
    public boolean isAvailable(String bankId) { return banks.isAvailable(bankId); }
}
