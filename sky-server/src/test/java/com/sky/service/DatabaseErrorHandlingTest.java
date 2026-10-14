package com.sky.service;

import com.sky.handler.GlobalExceptionHandler;
import com.sky.result.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseErrorHandlingTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void unsupportedCharacterIsAnExplicitBusinessFailureWithoutSqlLeak() {
        UncategorizedSQLException failure = new UncategorizedSQLException("insert", "private-sql",
                new SQLException("Incorrect string value: private-input for column remark", "HY000", 1366));
        ResponseEntity<Result<?>> response = handler.databaseExceptionHandler(failure);
        assertEquals(200, response.getStatusCodeValue());
        assertEquals(0, response.getBody().getCode());
        assertTrue(response.getBody().getMsg().contains("不支持的字符"));
        assertFalse(response.getBody().getMsg().contains("private"));
    }

    @Test void otherDatabaseErrorsRemainUncertainServerFailures() {
        UncategorizedSQLException failure = new UncategorizedSQLException("insert", "private-sql",
                new SQLException("Incorrect integer value: private-input", "HY000", 1366));
        ResponseEntity<Result<?>> response = handler.databaseExceptionHandler(failure);
        assertEquals(500, response.getStatusCodeValue());
        assertEquals(0, response.getBody().getCode());
        assertFalse(response.getBody().getMsg().contains("private"));
    }
}
