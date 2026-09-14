package com.parlour.api;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
@RestControllerAdvice
class Errors {
 @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> expected(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Request rejected":e.getReason()));}
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> constraint(Exception e){return ResponseEntity.status(409).body(Map.of("message","Duplicate or invalid related record"));}
 @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("message","Invalid input. Check dates, numbers and required fields."));}
}
