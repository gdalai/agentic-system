package com.assessment.agentic_system.common;

import com.assessment.agentic_system.shortener.CodeGenerator.CodeGenerationException;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlExpiredException;
import com.assessment.agentic_system.shortener.UrlExceptions.UrlNotFoundException;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain and validation exceptions to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UrlNotFoundException.class)
    public ProblemDetail notFound(UrlNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", ex.getMessage());
    }

    @ExceptionHandler(UrlExpiredException.class)
    public ProblemDetail expired(UrlExpiredException ex) {
        return problem(HttpStatus.GONE, "Expired", ex.getMessage());
    }

    @ExceptionHandler(CodeGenerationException.class)
    public ProblemDetail codeGeneration(CodeGenerationException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Code generation failed", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "Request body is invalid");
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a));
        pd.setProperty("errors", errors);
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
