package org.mengyun.tcctransaction.api;

import java.lang.reflect.Method;

/**
 * 事务上下文编辑器
 * 用于设置和获得事务上下文
 * Created by changming.xie on 1/18/17.
 */
public interface TransactionContextEditor {

    public TransactionContext get(Object target, Method method, Object[] args);

    public void set(TransactionContext transactionContext, Object target, Method method, Object[] args);

}
