package com.ld.poetry.handle;

import com.alibaba.fastjson2.JSON;
import com.ld.poetry.config.PoetryResult;
import com.ld.poetry.enums.CodeMsg;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class PoetryExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public PoetryResult handlerException(HttpServletRequest request, Exception ex) {
        log.error("请求URL-----------------{}", request.getRequestURL());
        log.error("出错啦------------------", ex);
        if (ex instanceof PoetryRuntimeException) {
            PoetryRuntimeException e = (PoetryRuntimeException) ex;
            return PoetryResult.fail(e.getMessage());
        }

        if (ex instanceof PoetryLoginException) {
            PoetryLoginException e = (PoetryLoginException) ex;
            return PoetryResult.fail(300, e.getMessage());
        }

        if (ex instanceof MethodArgumentNotValidException) {
            MethodArgumentNotValidException e = (MethodArgumentNotValidException) ex;
            Map<String, String> collect = e.getFieldErrors().stream().collect(Collectors.toMap(
                    FieldError::getField,
                    fieldError -> fieldError.getDefaultMessage() == null ? "参数校验失败" : fieldError.getDefaultMessage(),
                    (first, second) -> first + "；" + second));
            return PoetryResult.fail(JSON.toJSONString(collect));
        }

        if (ex instanceof MissingServletRequestParameterException) {
            return PoetryResult.fail(CodeMsg.PARAMETER_ERROR);
        }

        return PoetryResult.fail(CodeMsg.FAIL);
    }
}
