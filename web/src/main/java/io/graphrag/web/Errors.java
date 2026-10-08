package io.graphrag.web;

import java.util.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  public record Error(String code, String message) {}

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Error> missing(Exception e) {
    return response(404, "NOT_FOUND", e.getMessage());
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<Error> invalid(Exception e) {
    return response(
        400,
        "INVALID_INPUT",
        e instanceof IllegalArgumentException
            ? e.getMessage()
            : "Invalid request. Check required fields and search mode.");
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<Error> state(Exception e) {
    return response(409, "INVALID_STATE", e.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<Error> large() {
    return response(
        413,
        "UPLOAD_LIMIT",
        "Upload is too large. Reduce file sizes or configure MAX_FILE_SIZE and MAX_REQUEST_SIZE.");
  }

  @ExceptionHandler(ExplorerService.BusyException.class)
  ResponseEntity<Error> busy() {
    return response(503, "QUEUE_FULL", "Processing queue is full; retry after a corpus completes.");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> failed(Exception e) {
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed", e);
    return response(
        500,
        "PROCESSING_ERROR",
        "Operation failed. Check server logs and database/model configuration, then retry.");
  }

  private ResponseEntity<Error> response(int status, String code, String message) {
    return ResponseEntity.status(status).body(new Error(code, message));
  }
}
