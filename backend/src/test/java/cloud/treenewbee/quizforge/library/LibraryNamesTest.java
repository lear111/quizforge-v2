package cloud.treenewbee.quizforge.library;

import static org.assertj.core.api.Assertions.*;
import cloud.treenewbee.quizforge.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LibraryNamesTest {
    @ParameterizedTest
    @ValueSource(strings = {"bank.json", "MANIFEST.JSON", "development.json", ".隐藏", "结束 ", "结束.", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "CON", "con.txt", "LPT9", "COM1", "\u0001", "\u007f", "\u0085"})
    void rejectsLegacyForbiddenGroupNames(String name) {
        assertThatThrownBy(() -> LibraryNames.group(name)).isInstanceOf(ApiException.class);
    }

    @Test void boundariesAndDisplayNamesAreIndependentFromFolderRules() {
        assertThat(LibraryNames.group(null)).isNull();
        assertThat(LibraryNames.group("")).isNull();
        assertThat(LibraryNames.group("中".repeat(120))).hasSize(120);
        assertThatThrownBy(() -> LibraryNames.group("中".repeat(121))).isInstanceOf(ApiException.class);
        assertThat(LibraryNames.title(" a/b:显示名称 ")).isEqualTo("a/b:显示名称");
        assertThat(LibraryNames.title("😀".repeat(200))).hasSize(400);
        assertThatThrownBy(() -> LibraryNames.title("😀".repeat(201))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> LibraryNames.title("  ")).isInstanceOf(ApiException.class);
    }
}
