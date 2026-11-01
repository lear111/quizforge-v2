package cloud.treenewbee.quizforge.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import cloud.treenewbee.quizforge.shared.web.ApiException;

/** 联系方式规范化与旧平台 Contact.parse 一致（Issue #20 的验收写法）。 */
class ContactTest {

    @ParameterizedTest
    @ValueSource(strings = {"138 0013 8000", "+8613800138000", "8613800138000", "13800138000", "138-0013-8000",
            " 13800138000　"})
    void phoneSpellingsNormalizeToTheSameNumber(String raw) {
        assertThat(Contact.identifier(raw)).isEqualTo(new Contact(Contact.Channel.PHONE, "+8613800138000"));
        assertThat(Contact.phone(raw).value()).isEqualTo("+8613800138000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12800138000", "1380013800", "+85213800138000", "008613800138000", "1380013800a"})
    void invalidPhonesAreRejected(String raw) {
        assertThatThrownBy(() -> Contact.identifier(raw)).isInstanceOf(ApiException.class);
    }

    @Test
    void emailsAreTrimmedAndLowercased() {
        assertThat(Contact.identifier("﻿ Alice@Example.COM 　"))
                .isEqualTo(new Contact(Contact.Channel.EMAIL, "alice@example.com"));
        assertThatThrownBy(() -> Contact.phone("alice@example.com")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Contact.email("13800138000")).isInstanceOf(ApiException.class);
    }

    @Test
    void requestMustContainExactlyOneContactField() {
        assertThat(Contact.fromRequest(null, "a@example.com", null).isEmail()).isTrue();
        assertThatThrownBy(() -> Contact.fromRequest(null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Contact.fromRequest("a@example.com", "a@example.com", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void maskedFormHidesTheMiddle() {
        assertThat(Contact.phone("13800138000").masked()).isEqualTo("+86138****8000");
        assertThat(Contact.email("alice@example.com").masked()).isEqualTo("a***@example.com");
    }
}
