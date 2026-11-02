package cloud.treenewbee.quizforge.practice.management;

import static org.assertj.core.api.Assertions.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PracticeDirectoryNamesTest {
    @ParameterizedTest
    @ValueSource(strings = {"bank.json", "MANIFEST.JSON", "development.json", ".隐藏", "结束 ", "结束.", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "CON", "con.txt", "LPT9", "COM1", "\u0001", "\u007f", "\u0085"})
    void rejectsLegacyForbiddenGroupNames(String name) {
        assertThatThrownBy(() -> PracticeDirectoryNames.group(name)).isInstanceOf(ApiException.class);
    }

    @Test void boundariesAndDisplayNamesAreIndependentFromFolderRules() {
        assertThat(PracticeDirectoryNames.group(null)).isNull();
        assertThat(PracticeDirectoryNames.group("")).isNull();
        assertThat(PracticeDirectoryNames.group("中".repeat(120))).hasSize(120);
        assertThatThrownBy(() -> PracticeDirectoryNames.group("中".repeat(121))).isInstanceOf(ApiException.class);
        assertThat(PracticeDirectoryNames.title(" a/b:显示名称 ")).isEqualTo("a/b:显示名称");
        assertThat(PracticeDirectoryNames.title("😀".repeat(200))).hasSize(400);
        assertThatThrownBy(() -> PracticeDirectoryNames.title("😀".repeat(201))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PracticeDirectoryNames.title("  ")).isInstanceOf(ApiException.class);
    }
}
