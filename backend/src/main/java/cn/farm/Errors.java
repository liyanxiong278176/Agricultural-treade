package cn.farm;
import static cn.farm.Domain.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.*;
@RestControllerAdvice
public class Errors {
    @ExceptionHandler(Problem.class) public ResponseEntity<?> problem(Problem e){var body=new LinkedHashMap<String,Object>();body.put("message",e.getMessage());body.put("details",e.details);return ResponseEntity.status(e.status).body(body);}
    @ExceptionHandler(DuplicateKeyException.class) public ResponseEntity<?> duplicate(){return ResponseEntity.status(409).body(Map.of("message","工号、名称或记录已存在，请核对后重试"));}
    @ExceptionHandler({IllegalArgumentException.class,java.time.DateTimeException.class,org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) public ResponseEntity<?> bad(Exception e){return ResponseEntity.badRequest().body(Map.of("message","输入格式无效，请检查日期、数量及必填项"));}
    @ExceptionHandler(Exception.class) public ResponseEntity<?> unexpected(Exception e){org.slf4j.LoggerFactory.getLogger(Errors.class).error("Request failed",e);return ResponseEntity.status(500).body(Map.of("message","服务暂时无法处理，请稍后重试；本机待同步记录仍保留"));}
}
