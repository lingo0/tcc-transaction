package org.mengyun.tcctransaction.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.mengyun.tcctransaction.api.Compensable;
import org.mengyun.tcctransaction.api.Propagation;
import org.mengyun.tcctransaction.api.TransactionContext;
import org.mengyun.tcctransaction.common.MethodType;

import java.lang.reflect.Method;

/**
 * Created by changmingxie on 11/21/15.
 */
public class CompensableMethodUtils {

    /**
     * 获得带 @Compensable 注解的方法
     *
     * @param pjp 切面点
     * @return 方法
     */
    public static Method getCompensableMethod(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) (pjp.getSignature())).getMethod();

        if (method.getAnnotation(Compensable.class) == null) {
            try {
                method = pjp.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
        return method;
    }

    /**
     * 计算方法类型
     *
     * @param propagation 传播级别
     * @param isTransactionActive 是否事务开启
     * @param transactionContext 事务上下文
     * @return 方法类型
     */
    public static MethodType calculateMethodType(Propagation propagation, boolean isTransactionActive, TransactionContext transactionContext) {

        // 事务传播级别为 Propagation.REQUIRED，并且当前没有事务。
        if ((propagation.equals(Propagation.REQUIRED) && !isTransactionActive && transactionContext == null) ||
            // 事务传播级别为 Propagation.REQUIRES_NEW，新建事务，如果当前存在事务，把当前事务挂起。
            // 此时，事务管理器的当前线程事务队列可能会存在多个事务。
                propagation.equals(Propagation.REQUIRES_NEW)) {
            return MethodType.ROOT;
        } else if (
                // 事务传播级别为 Propagation.REQUIRED，并且当前不存在事务，并且方法参数传递了事务上下文。
                // 事务传播级别为 Propagation.PROVIDER，并且当前不存在事务，并且方法参数传递了事务上下文。
                (propagation.equals(Propagation.REQUIRED) || propagation.equals(Propagation.MANDATORY))
                   && !isTransactionActive && transactionContext != null) {
            return MethodType.PROVIDER;
        } else {
            return MethodType.NORMAL;
        }
    }

    public static MethodType calculateMethodType(TransactionContext transactionContext, boolean isCompensable) {

        if (transactionContext == null && isCompensable) {
            //isRootTransactionMethod
            return MethodType.ROOT;
        } else if (transactionContext == null && !isCompensable) {
            //isSoaConsumer
            return MethodType.CONSUMER;
        } else if (transactionContext != null && isCompensable) {
            //isSoaProvider
            return MethodType.PROVIDER;
        } else {
            return MethodType.NORMAL;
        }
    }

    public static int getTransactionContextParamPosition(Class<?>[] parameterTypes) {

        int position = -1;

        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].equals(org.mengyun.tcctransaction.api.TransactionContext.class)) {
                position = i;
                break;
            }
        }
        return position;
    }

    public static TransactionContext getTransactionContextFromArgs(Object[] args) {

        TransactionContext transactionContext = null;

        for (Object arg : args) {
            if (arg != null && org.mengyun.tcctransaction.api.TransactionContext.class.isAssignableFrom(arg.getClass())) {

                transactionContext = (org.mengyun.tcctransaction.api.TransactionContext) arg;
            }
        }

        return transactionContext;
    }
}
