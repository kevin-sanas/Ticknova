package com.ticknova.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> application(ApiException error,HttpServletRequest request) {
        return response(request,error.status(),error.code(),error.getMessage(),null);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,BindException.class})
    public ResponseEntity<ProblemDetail> validation(BindException error,HttpServletRequest request) {
        Map<String,String> fields=new LinkedHashMap<>();
        error.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(),Objects.requireNonNullElse(e.getDefaultMessage(),"字段无效")));
        return response(request,400,"VALIDATION_FAILED","请求字段无效",fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,ConstraintViolationException.class})
    public ResponseEntity<ProblemDetail> malformed(Exception error,HttpServletRequest request) {
        return response(request,400,"INVALID_REQUEST","请求格式或字段类型无效",null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> conflict(HttpServletRequest request) {
        return response(request,409,"DATA_CONFLICT","数据冲突，请检查编码或关联数据",null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> uploadTooLarge(HttpServletRequest request) { return response(request,413,"UPLOAD_TOO_LARGE","文件或请求超过允许大小",null); }
    @ExceptionHandler({MissingServletRequestPartException.class,MissingServletRequestParameterException.class})
    public ResponseEntity<ProblemDetail> missingPart(HttpServletRequest request) { return response(request,400,"INVALID_REQUEST","缺少必要的上传字段",null); }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) { return response(request,404,"NOT_FOUND","接口不存在",null); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception error,HttpServletRequest request) {
        // Do not log the exception message/stack: data access and conversion errors can embed request values.
        log.error("Unhandled request error type={} traceId={}",error.getClass().getSimpleName(),request.getAttribute(TraceFilter.ATTRIBUTE));
        return response(request,500,"INTERNAL_ERROR","服务暂时不可用，请凭追踪编号联系管理员",null);
    }

    private static ResponseEntity<ProblemDetail> response(HttpServletRequest request,int status,String code,String detail,Map<String,String> errors) {
        var problem=ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status),detail);
        problem.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        problem.setProperty("code",code); problem.setProperty("traceId",traceId(request));
        if (errors!=null) problem.setProperty("errors",errors);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    public static String traceId(HttpServletRequest request) {
        Object trace=request.getAttribute(TraceFilter.ATTRIBUTE);
        return trace==null?UUID.randomUUID().toString():trace.toString();
    }
}
