package cloud.treenewbee.quizforge.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleInputHashTest {
    @Test
    void keyOrderDoesNotChangeCacheIdentityButQuestionChangesDo() {
        Map<String,Object> before = new LinkedHashMap<>();
        before.put("a", 1);
        before.put("b", "中文");
        Map<String,Object> reversed = new LinkedHashMap<>();
        reversed.put("b", "中文");
        reversed.put("a", 1.0);
        assertThat(RuleInputHash.compute(before)).isEqualTo(RuleInputHash.compute(reversed));
        reversed.put("a", 2);
        assertThat(RuleInputHash.compute(before)).isNotEqualTo(RuleInputHash.compute(reversed));
        assertThat(RuleInputHash.capabilities()).isNotEqualTo(RuleInputHash.compute("capabilities"));
    }
}
