package org.mengyun.tcctransaction.interceptor;

import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.log4j.Logger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mengyun.tcctransaction.NoExistedTransactionException;
import org.mengyun.tcctransaction.SystemException;
import org.mengyun.tcctransaction.Transaction;
import org.mengyun.tcctransaction.TransactionManager;
import org.mengyun.tcctransaction.api.Compensable;
import org.mengyun.tcctransaction.api.Propagation;
import org.mengyun.tcctransaction.api.TransactionContext;
import org.mengyun.tcctransaction.api.TransactionStatus;
import org.mengyun.tcctransaction.common.MethodType;
import org.mengyun.tcctransaction.support.FactoryBuilder;
import org.mengyun.tcctransaction.utils.CompensableMethodUtils;
import org.mengyun.tcctransaction.utils.ReflectionUtils;
import org.mengyun.tcctransaction.utils.TransactionUtils;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * 可补偿拦截器
 * Created by changmingxie on 10/30/15.
 */
public class CompensableTransactionInterceptor {

    static final Logger logger = Logger.getLogger(CompensableTransactionInterceptor.class.getSimpleName());

    private TransactionManager transactionManager;

    private Set<Class<? extends Exception>> delayCancelExceptions;

    public void setTransactionManager(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void setDelayCancelExceptions(Set<Class<? extends Exception>> delayCancelExceptions) {
        this.delayCancelExceptions = delayCancelExceptions;
    }

    public Object interceptCompensableMethod(ProceedingJoinPoint pjp) throws Throwable {

        // 获得带 @Compensable 注解的方法
        Method method = CompensableMethodUtils.getCompensableMethod(pjp);

        Compensable compensable = method.getAnnotation(Compensable.class);
        // 获取传播级别
        Propagation propagation = compensable.propagation();
        // 获得 事务上下文
        TransactionContext transactionContext = FactoryBuilder.factoryOf(compensable.transactionContextEditor()).getInstance().get(pjp.getTarget(), method, pjp.getArgs());

        boolean asyncConfirm = compensable.asyncConfirm();

        boolean asyncCancel = compensable.asyncCancel();

        // 当前线程是否在事务中
        boolean isTransactionActive = transactionManager.isTransactionActive();

        // 判断事务上下文是否合法
        if (!TransactionUtils.isLegalTransactionContext(isTransactionActive, propagation, transactionContext)) {
            throw new SystemException("no active compensable transaction while propagation is mandatory for method " + method.getName());
        }

        // 计算方法类型
        // 根据不同方法类型，做不同的事务处理。
        MethodType methodType = CompensableMethodUtils.calculateMethodType(propagation, isTransactionActive, transactionContext);

        switch (methodType) {
            case ROOT:
                // 当方法类型为 MethodType.ROOT 时，调用 #rootMethodProceed(...) 方法，发起 TCC 整体流程。
                return rootMethodProceed(pjp, asyncConfirm, asyncCancel);
            case PROVIDER:
                // 方法类型为 MethodType.PROVIDER 时，发起分支事务,服务提供者参与 TCC 整体流程。
                return providerMethodProceed(pjp, transactionContext, asyncConfirm, asyncCancel);
            default:
                // MethodType.Normal
                // MethodType.CONSUMER 不进行事务处理
                return pjp.proceed();
        }
    }

    // 整体TCC流程
    private Object rootMethodProceed(ProceedingJoinPoint pjp, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

        Object returnValue = null;

        Transaction transaction = null;

        try {

            // 发起根事务，TCC Try 阶段开始。
            transaction = transactionManager.begin();

            // 执行方法原逻辑
            try {
                returnValue = pjp.proceed();
            } catch (Throwable tryingException) {

                if (!isDelayCancelException(tryingException)) { // 是否延迟回滚
                   
                    logger.warn(String.format("compensable transaction trying failed. transaction content:%s", JSON.toJSONString(transaction)), tryingException);

                    // 回滚事务
                    transactionManager.rollback(asyncCancel);
                }

                throw tryingException;
            }

            // 提交事务
            transactionManager.commit(asyncConfirm);

        } finally {
            // 将事务从当前线程事务队列移除
            transactionManager.cleanAfterCompletion(transaction);
        }

        return returnValue;
    }

    // 分支事务
    private Object providerMethodProceed(ProceedingJoinPoint pjp, TransactionContext transactionContext, boolean asyncConfirm, boolean asyncCancel) throws Throwable {

        Transaction transaction = null;
        try {

            switch (TransactionStatus.valueOf(transactionContext.getStatus())) {
                case TRYING:
                    // 传播发起分支事务
                    transaction = transactionManager.propagationNewBegin(transactionContext);
                    return pjp.proceed();
                case CONFIRMING:
                    try {
                        // 获取分支事务
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        // 提交事务
                        transactionManager.commit(asyncConfirm);
                    } catch (NoExistedTransactionException excepton) {
                        //the transaction has been commit,ignore it.
                    }
                    break;
                case CANCELLING:

                    try {
                        // 获取分支事务
                        transaction = transactionManager.propagationExistBegin(transactionContext);
                        // 回滚事务
                        transactionManager.rollback(asyncCancel);
                    } catch (NoExistedTransactionException exception) {
                        //the transaction has been rollback,ignore it.
                    }
                    break;
            }

        } finally {
            transactionManager.cleanAfterCompletion(transaction);
        }

        // 返回空值
        Method method = ((MethodSignature) (pjp.getSignature())).getMethod();

        //当事务处于 TransactionStatus.CONFIRMING / TransactionStatus.CANCELLING 时，
        // 调用 ReflectionUtils#getNullValue(...) 方法，返回空值。
        // Confirm / Cancel 相关方法，是通过 AOP 切面调用，只调用，不处理返回值，但是又不能没有返回值，因此直接返回空。
        return ReflectionUtils.getNullValue(method.getReturnType());
    }

    private boolean isDelayCancelException(Throwable throwable) {

        if (delayCancelExceptions != null) {
            for (Class delayCancelException : delayCancelExceptions) {

                Throwable rootCause = ExceptionUtils.getRootCause(throwable);

                if (delayCancelException.isAssignableFrom(throwable.getClass())
                        || (rootCause != null && delayCancelException.isAssignableFrom(rootCause.getClass()))) {
                    return true;
                }
            }
        }

        return false;
    }

}
