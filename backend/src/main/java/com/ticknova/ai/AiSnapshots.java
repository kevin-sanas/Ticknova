package com.ticknova.ai;

import com.ticknova.shared.ApiException;
import java.sql.SQLException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** A retry always gets a new RR transaction, snapshot and authorization checks. */
final class AiSnapshots {
    private final TransactionTemplate transaction;

    AiSnapshots(PlatformTransactionManager manager) {
        transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    <T> T execute(TransactionCallback<T> action) {
        for(int attempt=1;attempt<=3;attempt++) {
            try { return transaction.execute(action); }
            catch(RuntimeException failure) {
                if(!serializationFailure(failure))throw failure;
                if(attempt==3)throw new ApiException(409,"AI_RETRY_LATER","并发更新，请稍后重试");
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private static boolean serializationFailure(Throwable failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if(cause instanceof SQLException sql&&"40001".equals(sql.getSQLState()))return true;
            if(cause==cause.getCause())break;
        }
        return false;
    }
}
