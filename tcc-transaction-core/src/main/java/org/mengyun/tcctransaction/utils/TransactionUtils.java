package org.mengyun.tcctransaction.utils;

import org.mengyun.tcctransaction.api.Propagation;
import org.mengyun.tcctransaction.api.TransactionContext;

/**
 * Created by changming.xie on 2/23/17.
 */
public class TransactionUtils {

    /**
     * 判断事务上下文是否合法
     * 在 Propagation.MANDATORY 必须有在事务内
     *
     * 当传播级别为 Propagation.MANDATORY 时，要求必须在事务中。
     *
     * @param isTransactionActive 是否
     * @param propagation 传播级别
     * @param transactionContext 事务上下文
     * @return 是否合法
     */
    public static boolean isLegalTransactionContext(boolean isTransactionActive, Propagation propagation, TransactionContext transactionContext) {

        if (propagation.equals(Propagation.MANDATORY) && !isTransactionActive && transactionContext == null) {
            return false;
        }

        return true;
    }
}
