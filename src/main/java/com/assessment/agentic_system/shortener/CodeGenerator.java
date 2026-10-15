package com.assessment.agentic_system.shortener;

import java.security.SecureRandom;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Generates random Base62 short codes, retrying a bounded number of times on collision. */
@Component
public class CodeGenerator {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int CODE_LENGTH = 7;
    static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();

    /**
     * @param isTaken returns true when a candidate code already exists
     * @throws CodeGenerationException if no free code is found within {@value #MAX_ATTEMPTS} attempts
     */
    public String generate(Predicate<String> isTaken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }
        throw new CodeGenerationException("Could not generate a unique code after " + MAX_ATTEMPTS + " attempts");
    }

    String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static class CodeGenerationException extends RuntimeException {
        public CodeGenerationException(String message) {
            super(message);
        }
    }
}
