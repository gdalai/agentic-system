package com.assessment.agentic_system.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.shortener.CodeGenerator.CodeGenerationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void generatesBase62CodeOfFixedLength() {
        String code = generator.generate(c -> false);

        assertThat(code).hasSize(CodeGenerator.CODE_LENGTH).matches("[0-9A-Za-z]+");
    }

    @Test
    void retriesOnCollisionUntilFreeCodeFound() {
        AtomicInteger calls = new AtomicInteger();

        String code = generator.generate(c -> calls.incrementAndGet() < 3);

        assertThat(code).isNotBlank();
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void failsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> generator.generate(c -> {
            calls.incrementAndGet();
            return true;
        })).isInstanceOf(CodeGenerationException.class);
        assertThat(calls.get()).isEqualTo(CodeGenerator.MAX_ATTEMPTS);
    }
}
