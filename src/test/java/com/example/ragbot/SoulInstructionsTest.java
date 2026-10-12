package com.example.ragbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SoulInstructionsTest {
  @TempDir Path root;

  @Test
  void optionalFileUsesBundledInstructionsUntilALocalOverrideAppears() throws Exception {
    Path file = root.resolve("SOUL.md");
    var soul = new SoulInstructions("optional:" + file.toUri());
    String bundled = soul.read();
    assertThat(bundled).contains("SSBot", "financial business assistant", "Do not add unnecessary complexity.",
        "compare_portfolios", "[S#]/[B#]", "Do not store secrets");
    Files.writeString(file, "# SOUL\n本地设定：简短回答。\n");
    assertThat(soul.read()).isEqualTo("# SOUL\n本地设定：简短回答。");
    Files.writeString(file, "# SOUL\n修改后的设定。\n");
    assertThat(soul.read()).contains("修改后的设定").doesNotContain("本地设定");
  }

  @Test
  void explicitMissingOrEmptyFileFailsInsteadOfSilentlyReplacingInstructions() throws Exception {
    Path file = root.resolve("SOUL.md");
    assertThatThrownBy(() -> new SoulInstructions(file.toUri().toString()))
        .isInstanceOf(IOException.class).hasMessageContaining("not found");
    Files.writeString(file, "  \n");
    assertThatThrownBy(() -> new SoulInstructions("optional:" + file.toUri()))
        .isInstanceOf(IOException.class).hasMessageContaining("Empty");
  }

  @Test
  void invalidLiveEditsFailAndCorrectedEditsCanBeReadAgain() throws Exception {
    Path file = root.resolve("SOUL.md");
    Files.writeString(file, "Valid instructions");
    var soul = new SoulInstructions(file.toUri().toString());
    Files.writeString(file, "\n");
    assertThatThrownBy(soul::read).isInstanceOf(IOException.class).hasMessageContaining("Empty");
    Files.writeString(file, "Repaired instructions");
    assertThat(soul.read()).isEqualTo("Repaired instructions");
  }
}
