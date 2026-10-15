package com.assessment.agentic_system.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assessment.agentic_system.ai.RequirementAnalysis.InvalidAnalysisException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequirementAnalysisTest {

    @Test
    void parsesTaggedLinesAndIgnoresCommentary() {
        String reply = """
                Here is my analysis:
                - CRITERION: Short links redirect with 302
                * criterion: Expired links return 410
                AMBIGUITY: 'fast' is unmeasurable
                ASSUMPTION: p95 under 100 ms
                ASSUMPTION:
                Thanks!""";

        RequirementAnalysis analysis = RequirementAnalysis.parse(reply);

        assertThat(analysis.criteria()).containsExactly("Short links redirect with 302", "Expired links return 410");
        assertThat(analysis.ambiguities()).containsExactly("'fast' is unmeasurable");
        assertThat(analysis.assumptions()).containsExactly("p95 under 100 ms");
        assertThat(analysis.isAmbiguous()).isTrue();
    }

    @Test
    void rejectsReplyWithoutCriteria() {
        assertThatThrownBy(() -> RequirementAnalysis.parse("AMBIGUITY: everything"))
                .isInstanceOf(InvalidAnalysisException.class);
        assertThatThrownBy(() -> RequirementAnalysis.parse(null)).isInstanceOf(InvalidAnalysisException.class);
    }

    @Test
    void mockIsDeterministicAndFlagsVagueRequirements() {
        RequirementAnalyzer analyzer = new RequirementAnalyzer(new MockLlmClient());

        RequirementAnalysis clear = analyzer.analyze("Build a URL shortener with expiration and click analytics.");
        assertThat(clear.isAmbiguous()).isFalse();
        assertThat(clear.criteria()).hasSize(5);
        assertThat(analyzer.analyze("Build a URL shortener with expiration and click analytics.")).isEqualTo(clear);

        RequirementAnalysis vague = analyzer.analyze("Build a fast, scalable URL shortener with analytics etc.");
        assertThat(vague.ambiguities()).hasSize(3)
                .anySatisfy(a -> assertThat(a).contains("fast"))
                .anySatisfy(a -> assertThat(a).contains("scalable"))
                .anySatisfy(a -> assertThat(a).contains("etc"));
        assertThat(vague.assumptions()).hasSize(3);

        assertThat(analyzer.analyze("Shorten links").ambiguities()).anySatisfy(a -> assertThat(a).contains("brief"));
    }

    @Test
    void anthropicResponseTextIsExtractedFromTextBlocks() {
        Map<String, Object> response = Map.of("content", List.of(
                Map.of("type", "thinking", "thinking", "..."),
                Map.of("type", "text", "text", "CRITERION: a"),
                Map.of("type", "text", "text", "CRITERION: b")));

        assertThat(AnthropicLlmClient.extractText(response)).isEqualTo("CRITERION: a\nCRITERION: b");
        assertThatThrownBy(() -> AnthropicLlmClient.extractText(Map.of()))
                .isInstanceOf(LlmClient.LlmUnavailableException.class);
    }
}
