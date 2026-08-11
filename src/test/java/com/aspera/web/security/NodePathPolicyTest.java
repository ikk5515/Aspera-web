package com.aspera.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NodePathPolicyTest {

    @Test
    void normalizesSeparatorsWithoutChangingPathBoundaries() {
        assertThat(NodePathPolicy.normalizeAbsolutePath("/team//project/")).isEqualTo("/team/project");
        assertThat(NodePathPolicy.isSameOrDescendant("/team/file.txt", "/team")).isTrue();
        assertThat(NodePathPolicy.isSameOrDescendant("/team2/file.txt", "/team")).isFalse();
        assertThat(NodePathPolicy.overlaps("/", "/team/project")).isTrue();
    }

    @Test
    void rejectsTraversalAndNonPosixPaths() {
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team/../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team\\secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("relative/path"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team\nsecret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBoundaryWhitespaceInsteadOfAliasingPosixNames() {
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath(" /team/file.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team/file.txt "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team/ file.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath("/team/file.txt\u00a0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
        assertThat(NodePathPolicy.isSafeChildName("file.txt ")).isFalse();
        assertThat(NodePathPolicy.isSafeChildName(" file.txt")).isFalse();
    }

    @Test
    void safelyJoinsOneFolderName() {
        assertThat(NodePathPolicy.join("/team/", "project")).isEqualTo("/team/project");
        assertThatThrownBy(() -> NodePathPolicy.join("/team", "../secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodePathPolicy.join("/team", "nested/folder"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodePathPolicy.join("/team", "project "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whitespace");
    }

    @Test
    void enforcesTransportSafeUtf8PathBudget() {
        String asciiWithinLimit = "/" + String.join("/", java.util.Collections.nCopies(8, "a".repeat(255)));
        String asciiOverLimit = asciiWithinLimit + "a";
        String koreanWithinLimit = "/" + "가".repeat(255) + "/" + "나".repeat(255);
        String koreanOverLimit = koreanWithinLimit + "/" + "다".repeat(255);
        String emojiSegment = "😀".repeat(127);
        String emojiWithinLimit = "/" + String.join("/", java.util.Collections.nCopies(4, emojiSegment));
        String emojiOverLimit = emojiWithinLimit + "/" + emojiSegment;

        assertThat(NodePathPolicy.normalizeAbsolutePath(asciiWithinLimit)).isEqualTo(asciiWithinLimit);
        assertThat(NodePathPolicy.normalizeAbsolutePath(koreanWithinLimit)).isEqualTo(koreanWithinLimit);
        assertThat(NodePathPolicy.normalizeAbsolutePath(emojiWithinLimit)).isEqualTo(emojiWithinLimit);
        for (String path : java.util.List.of(asciiOverLimit, koreanOverLimit, emojiOverLimit)) {
            assertThatThrownBy(() -> NodePathPolicy.normalizeAbsolutePath(path))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2048-byte UTF-8");
        }
    }
}
