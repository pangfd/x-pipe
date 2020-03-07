package com.ctrip.xpipe.redis.keeper.ratelimit;

import com.ctrip.xpipe.redis.core.protocal.Psync;
import com.ctrip.xpipe.redis.core.protocal.protocal.EofType;
import com.ctrip.xpipe.redis.keeper.RedisKeeperServer;
import com.ctrip.xpipe.redis.keeper.RedisMasterReplication;
import com.ctrip.xpipe.redis.keeper.config.KeeperConfig;
import com.ctrip.xpipe.redis.keeper.config.KeeperResourceManager;
import com.ctrip.xpipe.redis.keeper.impl.RedisKeeperServerStateActive;
import com.ctrip.xpipe.redis.keeper.monitor.KeeperStats;
import com.ctrip.xpipe.redis.keeper.monitor.PsyncFailReason;
import com.ctrip.xpipe.utils.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author chen.zhu
 * <p>
 * Mar 02, 2020
 */
public class LeakyBucketBasedMasterReplicationListener implements RedisMasterReplication.RedisMasterReplicationObserver {

    private static final Logger logger = LoggerFactory.getLogger(LeakyBucketBasedMasterReplicationListener.class);

    private RedisMasterReplication redisMasterReplication;

    private RedisKeeperServer redisKeeperServer;

    private KeeperResourceManager resourceManager;

    protected AtomicBoolean holdToken = new AtomicBoolean(false);

    private ScheduledExecutorService scheduled;

    protected ScheduledFuture<?> releaseTokenFuture;

    private final int INIT_STATE = 1, EVER_SUCCEED = 2, KEEP_FAIL = 3;
    private AtomicInteger psyncEverSucceed = new AtomicInteger(INIT_STATE);

    private AtomicLong psyncSendUnixTime;

    private AtomicInteger trafficSafeCounter;

    public LeakyBucketBasedMasterReplicationListener(RedisMasterReplication redisMasterReplication,
                                                     RedisKeeperServer redisKeeperServer,
                                                     KeeperResourceManager resourceManager,
                                                     ScheduledExecutorService scheduled) {
        this.redisMasterReplication = redisMasterReplication;
        this.redisKeeperServer = redisKeeperServer;
        this.resourceManager = resourceManager;
        this.scheduled = scheduled;
    }

    @Override
    public void onMasterConnected() {
        initAndClear();
    }

    @Override
    public boolean canSendPsync() {
        if (redisMasterReplication.redisMaster().isKeeper()
                && (redisKeeperServer.getRedisKeeperServerState() instanceof RedisKeeperServerStateActive)) {
            // for those who always fails, let it go
            KeeperStats keeperStats = redisKeeperServer.getKeeperMonitor().getKeeperStats();
            if(keeperStats.getLastPsyncFailReason() != null && keeperStats.getLastPsyncFailReason() != PsyncFailReason.TOKEN_LACK
                    && psyncEverSucceed.get() != INIT_STATE && !isPsyncEverSucceed()) {
                logger.warn("[canSendPsync]never succeed, let it psync, {}", psyncEverSucceed.get());
                return true;
            }
            if(resourceManager.getLeakyBucket().tryAcquire()) {
                holdToken.set(true);
                recordPsyncSendTime();
            } else {
                logger.warn("[canSendPsync]psync wont send as no token is available [port:{}]", redisKeeperServer.getListeningPort());
                keeperStats.setLastPsyncFailReason(PsyncFailReason.TOKEN_LACK);
                keeperStats.increasePsyncSendFail();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onMasterDisconnected() {
        if (holdToken.compareAndSet(true, false)) {
            resourceManager.getLeakyBucket().release();
        }
        if (releaseTokenFuture != null && !releaseTokenFuture.isDone()) {
            releaseTokenFuture.cancel(true);
            releaseTokenFuture = null;
        }
    }

    @Override
    public void endWriteRdb() {
        if (holdToken.compareAndSet(true, false)) {
            resourceManager.getLeakyBucket().release();
            initPsyncState();
        }
    }

    @Override
    public void onContinue(String requestReplId, String responseReplId) {
        if(holdToken.get()) {
            int rtt = (int) (System.currentTimeMillis() - psyncSendUnixTime.get());
            setPsyncSucceed();
            logger.info("[onContinue][rtt] {}", rtt);
            tryDelayReleaseToken(rtt);
        }
    }

    @Override
    public void onFullSync() {
        setPsyncSucceed();
    }

    @Override
    public void reFullSync() {
    }

    @Override
    public void onDumpFinished() {

    }

    @Override
    public void onDumpFail() {
        setPsyncFailed();
        if (holdToken.compareAndSet(true, false)) {
            resourceManager.getLeakyBucket().release();
        }
    }

    @Override
    public void beginWriteRdb(EofType eofType, long masterRdbOffset) throws IOException {

    }

    private void initAndClear() {
        holdToken.set(false);
        if (releaseTokenFuture != null) {
            releaseTokenFuture.cancel(true);
            releaseTokenFuture = null;
        }
    }

    private void releaseToken() {
        holdToken.set(false);
        resourceManager.getLeakyBucket().release();
    }

    private void recordPsyncSendTime() {
        if (psyncSendUnixTime == null) {
            psyncSendUnixTime = new AtomicLong(System.currentTimeMillis());
        } else {
            psyncSendUnixTime.set(System.currentTimeMillis());
        }
    }

    private void tryDelayReleaseToken(int rtt) {
        if (holdToken.get()) {
            KeeperConfig keeperConfig = redisKeeperServer.getKeeperConfig();
            // num(files) * file-size / cross-dc-replication-rate
            logger.info("[deadline]1000 * {} * {} / {}", keeperConfig.getReplicationStoreCommandFileNumToKeep(),
                    keeperConfig.getReplicationStoreCommandFileSize(), keeperConfig.getReplicationTrafficHighWaterMark());
            long afterMilli = 1000L * keeperConfig.getReplicationStoreCommandFileNumToKeep()
                    * keeperConfig.getReplicationStoreCommandFileSize() / keeperConfig.getReplicationTrafficHighWaterMark();
            logger.info("[afterMilli]{}", afterMilli);
            long deadline = afterMilli + System.currentTimeMillis();
            int checkInterval = rtt * keeperConfig.getPartialSyncTrafficMonitorIntervalTimes();
            // 100ms < checkInterval < 1000
            checkInterval = Math.min(1000, Math.max(100, checkInterval));
            logger.info("[tryDelayReleaseToken]deadline: {}, check-interval: {}", DateTimeUtils.timeAsString(deadline), checkInterval);
            checkIfNeedReleaseToken(deadline, checkInterval);
        }
    }

    //either obvious speed shrink down, or under pre-defined threshold (low water mark)
    //we consider TCP traffic is safe enough to release the token
    private void checkIfNeedReleaseToken(final long deadline, final int checkPartialSyncInterval) {
        releaseTokenFuture = scheduled.schedule(new Runnable() {
            @Override
            public void run() {
                if (holdToken.get()) {
                    if(isTokenReadyToRelease(deadline)) {
                        logger.info("[checkIfNeedReleaseToken][release] deadline");
                        initPsyncState();
                        releaseToken();
                    } else {
                        checkIfNeedReleaseToken(deadline, checkPartialSyncInterval);
                    }
                }
            }
        }, checkPartialSyncInterval, TimeUnit.MILLISECONDS);
    }

    private boolean isTokenReadyToRelease(final long deadline) {
        int oneSec = 1000;
        if (System.currentTimeMillis() >= deadline || Math.abs(System.currentTimeMillis() - deadline) < oneSec) {
            return true;
        }
        if(trafficSafeCounter == null) {
            trafficSafeCounter = new AtomicInteger();
        }
        KeeperStats keeperStats = redisKeeperServer.getKeeperMonitor().getKeeperStats();
        long peakBPS = keeperStats.getPeakInputInstantaneousBPS();
        long curBPS = keeperStats.getPeakInputInstantaneousBPS();
        if(curBPS < peakBPS / 2 || curBPS < redisKeeperServer.getKeeperConfig().getReplicationTrafficLowWaterMark()) {
            trafficSafeCounter.incrementAndGet();
        }
        if(trafficSafeCounter.get() > 2) {
            return true;
        }
        return false;
    }

    private void setPsyncFailed() {
        redisKeeperServer.getKeeperMonitor().getKeeperStats().setLastPsyncFailReason(PsyncFailReason.MASTER_DISCONNECTED);
        if(!isPsyncEverSucceed()) {
            psyncEverSucceed.set(KEEP_FAIL);
        }
    }

    private void setPsyncSucceed() {
        psyncEverSucceed.set(EVER_SUCCEED);
        redisKeeperServer.getKeeperMonitor().getKeeperStats().setLastPsyncFailReason(null);
    }

    private boolean isPsyncEverSucceed() {
        return psyncEverSucceed.get() == EVER_SUCCEED;
    }

    private void initPsyncState() {
        psyncEverSucceed.set(INIT_STATE);
        logger.info("[initPsyncState] {}", psyncEverSucceed.get() == INIT_STATE);
    }
}
