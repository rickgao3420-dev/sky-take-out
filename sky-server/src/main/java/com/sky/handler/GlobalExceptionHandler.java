package com.sky.handler;

import com.sky.constant.MessageConstant;
import com.sky.exception.BaseException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /** 不泄露 SQL/输入内容；字符集不兼容时事务已经回滚，允许用户修改输入后重试。 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<?>> databaseExceptionHandler(DataAccessException ex) {
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SQLException) {
                SQLException sql = (SQLException) cause;
                if (sql.getErrorCode() == 1366 && sql.getMessage() != null &&
                        sql.getMessage().contains("Incorrect string value")) {
                    log.warn("数据库字符集拒绝了输入中的字符，事务操作已失败");
                    return ResponseEntity.ok(Result.error("输入包含当前数据库不支持的字符，请移除表情等特殊字符后重试"));
                }
            }
            cause = cause.getCause();
        }
        // 其他数据库错误不能伪装成明确业务拒绝：保留 500，让客户端核对写入结果。
        log.error("数据库访问失败，异常类型：{}", ex.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Result.error("数据服务暂不可用，请核对操作结果后重试"));
    }

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    @ExceptionHandler
    public Result exceptionHandler(SQLIntegrityConstraintViolationException ex){
         String message = ex.getMessage();
         if (message.contains("Duplicate entry")){
             String[] split = message.split(" ");
             String username = split[2];
             String msg = username + MessageConstant.ALREADY_EXISTS;
             return Result.error(  msg );
         }else{
             return Result.error( MessageConstant.UNKNOWN_ERROR );
         }


    }

}
