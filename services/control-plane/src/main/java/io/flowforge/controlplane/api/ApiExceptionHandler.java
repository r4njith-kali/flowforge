package io.flowforge.controlplane.api;

import io.flowforge.controlplane.idempotency.api.InvalidIdempotencyKeyException;
import io.flowforge.controlplane.idempotency.domain.IdempotencyConflictException;
import io.flowforge.controlplane.job.api.InvalidJobQueryException;
import io.flowforge.controlplane.job.domain.InvalidJobStateException;
import io.flowforge.controlplane.job.domain.JobNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail handleNotFound(JobNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler({
            InvalidJobStateException.class,
            IdempotencyConflictException.class,
            OptimisticLockingFailureException.class
    })
    ProblemDetail handleConflict(RuntimeException exception, HttpServletRequest request) {
        String code = exception instanceof IdempotencyConflictException
                ? "IDEMPOTENCY_KEY_REUSED"
                : "JOB_STATE_CONFLICT";
        return problem(HttpStatus.CONFLICT, code, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidJobQueryException.class)
    ProblemDetail handleInvalidJobQuery(
            InvalidJobQueryException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_QUERY", exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request);
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                .distinct()
                .toList();
        detail.setProperty("errors", errors);
        return detail;
    }

    @ExceptionHandler({
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ProblemDetail handleInvalidRequest(HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "A request parameter or JSON body is invalid",
                request);
    }

    private static ProblemDetail problem(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }

    private record ValidationError(String field, String message) {
    }
}
