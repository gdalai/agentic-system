package com.assessment.agentic_system.ai;

import org.springframework.stereotype.Component;

/** Owns the requirement-analysis prompt and turns the model's reply into validated structure. */
@Component
public class RequirementAnalyzer {

    static final String SYSTEM_PROMPT = """
            You are a senior software engineer turning a natural-language requirement into
            testable acceptance criteria. Reply ONLY with lines in exactly this format:
            CRITERION: <one testable acceptance criterion>
            AMBIGUITY: <anything vague, unmeasurable or open-ended that blocks a precise design>
            ASSUMPTION: <the assumption you would make for each ambiguity if no one clarifies it>
            Write 3 to 8 CRITERION lines. Write AMBIGUITY and ASSUMPTION lines only when needed.
            Do not write anything else.""";

    static final String USER_PREFIX = "Requirement:\n";

    private final LlmClient llm;

    public RequirementAnalyzer(LlmClient llm) {
        this.llm = llm;
    }

    public String modelName() {
        return llm.name();
    }

    public RequirementAnalysis analyze(String requirement) {
        return RequirementAnalysis.parse(llm.complete(SYSTEM_PROMPT, USER_PREFIX + requirement));
    }
}
