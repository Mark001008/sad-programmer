package com.mark.common.demo;

import java.util.concurrent.ThreadPoolExecutor;

import javax.annotation.Resource;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.TtlRunnable;
import com.mark.common.factory.log.LFLog;
import com.mark.common.factory.log.LogFactory;

/**
 * <p>描述: TransmittableThreadLocal demo </p>
 * <p>创建时间: 2024/11/14 10:54 </p>
 * 解决了在使用线程池等会池化复用线程的执行组件情况下传递ThreadLocal值的问题
 *
 * @author Mark
 */
public class TransmittableThreadLocalDemo {

    private static final LFLog LOG = LogFactory.getLogger(TransmittableThreadLocalDemo.class);

    @Resource
    private static ThreadPoolExecutor myExecutor;


    public static void main(String[] args) {
        TransmittableThreadLocal<String> ttl = new TransmittableThreadLocal<>();
        ttl.set("value");
        myExecutor.submit(TtlRunnable.get(() -> {
            String value = ttl.get();
            // 使用传递过来的值
            LOG.info("Value from TransmittableThreadLocal: " + value);
        }));
    }
}
