package com.assessment.agentic_system.governance;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who may approve or reject a gated task. Configured with {@code orchestration.approvers}
 * (comma-separated). In a real deployment this would come from the identity provider.
 */
@Component
public class ApproverPolicy {

    private final Set<String> approvers;

    public ApproverPolicy(@Value("${orchestration.approvers:tech-lead,release-manager}") String approvers) {
        this.approvers = Arrays.stream(approvers.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public void requireAuthorized(String approver) {
        if (approver == null || !approvers.contains(approver.trim())) {
            throw new ApprovalNotAuthorizedException(approver);
        }
    }

    public static class ApprovalNotAuthorizedException extends RuntimeException {
        public ApprovalNotAuthorizedException(String approver) {
            super("'" + approver + "' is not an authorized approver");
        }
    }
}
