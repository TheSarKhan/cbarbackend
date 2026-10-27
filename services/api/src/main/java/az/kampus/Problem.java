package az.kampus;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
class Problem extends RuntimeException {
 final int status; final String code;
 Problem(int status,String code,String detail){super(detail);this.status=status;this.code=code;}
 static void require(boolean condition,String code,String detail){if(!condition)throw new Problem(422,code,detail);}
}
class CredentialFailure extends Problem {final Integer remainingAttempts;CredentialFailure(String detail){this(detail,null);}CredentialFailure(String detail,Integer remainingAttempts){super(401,"INVALID_CREDENTIALS",detail);this.remainingAttempts=remainingAttempts;}}
@RestControllerAdvice class ProblemHandler {
 @ExceptionHandler(Problem.class) ResponseEntity<ProblemDetail> problem(Problem e){var p=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(e.status),e.getMessage());p.setTitle(e.code);p.setProperty("code",e.code);if(e instanceof CredentialFailure c&&c.remainingAttempts!=null){p.setProperty("remainingAttempts",c.remainingAttempts);p.setProperty("locked",c.remainingAttempts==0);}return ResponseEntity.status(e.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<ProblemDetail> invalid(Exception e){return problem(new Problem(400,"INVALID_REQUEST","Sorğunun formatı yanlışdır."));}
}
