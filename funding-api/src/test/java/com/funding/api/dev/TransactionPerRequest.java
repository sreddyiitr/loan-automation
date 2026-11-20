package com.funding.api.dev;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Standalone MockMvc doesn't proxy {@code @Transactional} controllers. This runs each request in one
 * transaction, like the app: committed on success, rolled back on an exception or an error status.
 */
final class TransactionPerRequest implements HandlerInterceptor {

    private static final String KEY = TransactionPerRequest.class.getName();
    private final PlatformTransactionManager tm;

    TransactionPerRequest(TransactionTemplate tx) {
        this.tm = tx.getTransactionManager();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(KEY, tm.getTransaction(new DefaultTransactionDefinition()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TransactionStatus status = (TransactionStatus) request.getAttribute(KEY);
        if (status == null || status.isCompleted()) {
            return;
        }
        if (ex != null || response.getStatus() >= 400) {
            tm.rollback(status);
        } else {
            tm.commit(status);
        }
    }
}
