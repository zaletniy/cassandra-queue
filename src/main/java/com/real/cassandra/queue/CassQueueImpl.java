package com.real.cassandra.queue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.management.InstanceAlreadyExistsException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.real.cassandra.queue.locks.Locker;
import com.real.cassandra.queue.pipes.PipeManager;
import com.real.cassandra.queue.repository.QueueRepositoryImpl;
import com.real.cassandra.queue.utils.JmxMBeanManager;
import com.real.cassandra.queue.utils.RollingStat;

public class CassQueueImpl implements CassQueueMXBean {
    private static Logger logger = LoggerFactory.getLogger(CassQueueImpl.class);

    public static final long TRANSACTION_GRACE_PERIOD = 2000;

    private QueueDescriptor qDesc;
    private QueueRepositoryImpl qRepos;

    private Locker<QueueDescriptor> pipeCollectionLocker;
    private Locker<QueueDescriptor> queueStatsLocker;
    private PusherImpl rollbackPusher;

    private Set<PusherImpl> pusherSet = new HashSet<PusherImpl>();
    private Set<PopperImpl> popperSet = new HashSet<PopperImpl>();

    private RollingStat popNotEmptyStat = new RollingStat(60000);
    private RollingStat popEmptyStat = new RollingStat(60000);
    private RollingStat pushStat = new RollingStat(60000);

    private PipeReaper pipeReaper;

    public CassQueueImpl(QueueRepositoryImpl qRepos, QueueDescriptor qDesc, boolean startReaper,
            Locker<QueueDescriptor> queueStatsLocker, Locker<QueueDescriptor> pipeCollectionLocker) {
        this.qDesc = qDesc;
        this.qRepos = qRepos;
        this.queueStatsLocker = queueStatsLocker;
        this.pipeCollectionLocker = pipeCollectionLocker;

        logger.debug("creating pusher for rollback only");
        this.rollbackPusher = createPusher();

        if (startReaper) {
            this.pipeReaper = new PipeReaper(this, qRepos, this.queueStatsLocker);
            this.pipeReaper.start();
        }

        initJmx();
    }

    private void initJmx() {
        String beanName = JMX_MBEAN_OBJ_NAME_PREFIX + qDesc.getName();
        try {
            JmxMBeanManager.getInstance().registerMBean(this, beanName);
        }
        catch (InstanceAlreadyExistsException e1) {
            logger.warn("exception while registering MBean, " + beanName + " - ignoring");
        }
        catch (Exception e) {
            throw new RuntimeException("exception while registering MBean, " + beanName);
        }
    }

    public boolean checkTransactionTimeoutExpired(CassQMsg qMsg) {
        return System.currentTimeMillis() - qMsg.getMsgDesc().getPopTimestamp() > (getTransactionTimeout() + TRANSACTION_GRACE_PERIOD);
    }

    public void commit(CassQMsg qMsg) {
        logger.debug("commit {}", qMsg);
        qRepos.removeMsgFromPendingPipe(qMsg);
    }

    public CassQMsg rollback(CassQMsg qMsg) {
        logger.debug("rollback {}", qMsg);
        CassQMsg qNewMsg = rollbackPusher.push(qMsg.getMsgDesc().getPayload());
        qRepos.removeMsgFromPendingPipe(qMsg);
        return qNewMsg;
    }

    public PusherImpl createPusher() {
        logger.debug("creating pusher for queue {}", qDesc.getName());
        PusherImpl pusher = new PusherImpl(this, qRepos, pushStat);
        pusherSet.add(pusher);
        return pusher;
    }

    public PopperImpl createPopper() {
        logger.debug("creating popper for queue {}", qDesc.getName());
        UUID popperId = UUID.randomUUID();
        PipeManager pipeMgr = new PipeManager(qRepos, this, popperId, pipeCollectionLocker);
        PopperImpl popper = new PopperImpl(popperId, this, qRepos, pipeMgr, popNotEmptyStat, popEmptyStat);
        popperSet.add(popper);
        return popper;
    }

    public void drop() throws Exception {
        logger.debug("dropping queue {}", qDesc.getName());
        qRepos.dropQueue(this);
    }

    public void truncate() throws Exception {
        logger.debug("truncating queue {}", qDesc.getName());
        qRepos.truncateQueueData(this);
    }

    @Override
    public long getMaxPushTimePerPipe() {
        return qDesc.getMaxPushTimePerPipe();
    }

    @Override
    public void setMaxPushTimePerPipe(long maxPushTimeOfPipe) {
        qDesc.setMaxPushTimePerPipe(maxPushTimeOfPipe);
    }

    @Override
    public int getMaxPushesPerPipe() {
        return qDesc.getMaxPushesPerPipe();
    }

    @Override
    public void setMaxPushesPerPipe(int maxPushesPerPipe) {
        qDesc.setMaxPushesPerPipe(maxPushesPerPipe);
    }

    @Override
    public String getName() {
        return qDesc.getName();
    }

    public void forcePipeReaperWakeUp() {
        pipeReaper.wakeUp();
    }

    public void shutdownAndWait() {
        for (PusherImpl pusher : pusherSet) {
            pusher.shutdownAndWait();
        }

        for (PopperImpl popper : popperSet) {
            popper.shutdownAndWait();
        }

        if (null != pipeReaper) {
            pipeReaper.shutdownAndWait();
        }
    }

    @Override
    public long getPopCountLocalNotEmpty() {
        return popNotEmptyStat.getTotalSamplesProcessed();
    }

    @Override
    public long getPopCountLocalEmpty() {
        return popEmptyStat.getTotalSamplesProcessed();
    }

    @Override
    public long getPopCountCluster() {
        QueueStats qStats = qRepos.getQueueStats(getName());
        return qStats.getTotalPops();
    }

    @Override
    public double getPopAvgTimeLocal_NotEmpty() {
        return popNotEmptyStat.getAvgOfValues();
    }

    @Override
    public double getPopPerSecondLocal_NotEmpty() {
        return popNotEmptyStat.getSamplesPerSecond();
    }

    @Override
    public long getPushCountLocal() {
        return pushStat.getTotalSamplesProcessed();
    }

    @Override
    public long getPushCountCluster() {
        QueueStats qStats = qRepos.getQueueStats(getName());
        return qStats.getTotalPushes();
    }

    @Override
    public double getPushAvgTimeLocal() {
        return pushStat.getAvgOfValues();
    }

    @Override
    public double getPushPerSecondLocal() {
        return pushStat.getSamplesPerSecond();
    }

    @Override
    public int getNumPoppers() {
        return popperSet.size();
    }

    @Override
    public int getNumPushers() {
        return pusherSet.size();
    }

    public void setPipeReaperProcessingDelay(long delay) {
        pipeReaper.setProcessingDelay(delay);
    }

    public long getTransactionTimeout() {
        return qDesc.getTransactionTimeout();
    }

    public void setTransactionTimeout(long transactionTimeout) {
        qDesc.setTransactionTimeout(transactionTimeout);
    }

    public QueueDescriptor getQueueDescriptor() {
        return qDesc;
    }

    PipeReaper getPipeReaper() {
        return pipeReaper;
    }
}
