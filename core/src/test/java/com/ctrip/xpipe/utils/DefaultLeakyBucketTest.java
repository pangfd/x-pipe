package com.ctrip.xpipe.utils;

import com.ctrip.xpipe.AbstractTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author chen.zhu
 * <p>
 * Feb 17, 2020
 */
public class DefaultLeakyBucketTest extends AbstractTest {

    private DefaultLeakyBucket bucket;
    private int init = 1;

    @Before
    public void beforeLeakyBucketTest() {

        bucket = new DefaultLeakyBucket(()->init);
    }

    @Test
    public void testRelease() throws InterruptedException {
        int task = init * 100;
        CountDownLatch latch = new CountDownLatch(task);
        for (int i = 0; i < task; i++) {
            executors.execute(new Runnable() {
                @Override
                public void run() {
                    if (bucket.tryAcquire()) {
                        bucket.release();
                    }
                    latch.countDown();
                }
            });
        }
        latch.await(10000, TimeUnit.MILLISECONDS);
        Assert.assertTrue(init >= bucket.references());
    }

    @Test
    public void testTryAcquire() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        int task = init * 100;
        CountDownLatch latch = new CountDownLatch(task);
        for (int i = 0; i < task; i++) {
            executors.execute(new Runnable() {
                @Override
                public void run() {
                    if (bucket.tryAcquire()) {
                        counter.incrementAndGet();
                    }
                    latch.countDown();
                }
            });
        }
        latch.await(1000, TimeUnit.MILLISECONDS);
        Assert.assertEquals(1, counter.get());
    }

    @Test
    public void testReset() {
        int tasks = 1;
        while(tasks++ < 20) {
            bucket.tryAcquire();
            bucket.release();
        }
        bucket.reset();
        Assert.assertEquals(init, bucket.references());
    }

    @Test
    public void testResize() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        int task = init * 100, newSize = 10;
        CountDownLatch latch = new CountDownLatch(task);
        CyclicBarrier barrier = new CyclicBarrier(task + 1);
        executors.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    barrier.await();
                } catch (Exception ignore) {
                }
                bucket.resize(newSize);
            }
        });
        for (int i = 0; i < task; i++) {
            executors.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                    } catch (Exception ignore) {
                    }
                    if (bucket.tryAcquire()) {
                        counter.incrementAndGet();
                    }
                    latch.countDown();
                }
            });
        }
        latch.await(1000, TimeUnit.MILLISECONDS);
        Assert.assertEquals(newSize, counter.get());
    }


    @Test
    public void testResizeNotBreakTheTaskLaw() throws InterruptedException {
        AtomicInteger counter = new AtomicInteger();
        int task = init * 100, newSize = 10;
        CountDownLatch latch = new CountDownLatch(task);
        CyclicBarrier barrier = new CyclicBarrier(task + 1);
        executors.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    barrier.await();
                } catch (Exception ignore) {
                }
                bucket.resize(newSize);
            }
        });
        for (int i = 0; i < task; i++) {
            executors.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await();
                    } catch (Exception ignore) {
                    }
                    if (bucket.tryAcquire()) {
                        counter.incrementAndGet();
                        bucket.release();
                    }
                    latch.countDown();
                }
            });
        }
        latch.await(1000, TimeUnit.MILLISECONDS);
        Assert.assertEquals(newSize, bucket.references());
    }
}