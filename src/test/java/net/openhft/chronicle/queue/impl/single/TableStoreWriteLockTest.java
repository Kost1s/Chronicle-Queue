package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.onoes.LogLevel;
import net.openhft.chronicle.queue.QueueTestCommon;
import net.openhft.chronicle.queue.TableStoreWriteLockLockerProcess;
import net.openhft.chronicle.queue.common.ProcessRunner;
import net.openhft.chronicle.queue.impl.TableStore;
import net.openhft.chronicle.queue.impl.table.Metadata;
import net.openhft.chronicle.queue.impl.table.SingleTableBuilder;
import net.openhft.chronicle.threads.Pauser;
import net.openhft.chronicle.threads.Threads;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.Assert.*;

public class TableStoreWriteLockTest extends QueueTestCommon {

    private static final String TEST_LOCK_NAME = "testLock";
    private static final long TIMEOUT_MS = 100;
    private TableStore<Metadata.NoMeta> tableStore;
    private Path storeDirectory;

    @Before
    public void setUp() {
        final Path tempDir = IOTools.createTempDirectory("namedTableStoreLockTest");
        tempDir.toFile().mkdirs();
        storeDirectory = tempDir.resolve("test_store.cq4t");
        tableStore = SingleTableBuilder.binary(storeDirectory, Metadata.NoMeta.INSTANCE).build();
    }

    @After
    public void tearDown() {
        Closeable.closeQuietly(tableStore);
    }

    @Test(timeout = 5_000)
    public void lockWillThrowIllegalStateExceptionIfInterruptedWhileWaitingForLock() throws InterruptedException {
        try (final TableStoreWriteLock testLock = createTestLock(tableStore, 5_000)) {
            testLock.lock();
            AtomicBoolean threwException = new AtomicBoolean(false);
            Thread t = new Thread(() -> {
                try {
                    testLock.lock();
                } catch (IllegalStateException e) {
                    threwException.set(true);
                }
            });
            t.start();
            Jvm.pause(10);
            t.interrupt();
            t.join();
            assertTrue(threwException.get());
        }
    }

    @Test
    public void testIsLockedByCurrentProcess() {
        AtomicLong actualPid = new AtomicLong(-1);
        try (final TableStoreWriteLock testLock = createTestLock()) {
            testLock.lock();
            assertTrue(testLock.isLockedByCurrentProcess(actualPid::set));
            assertEquals(-1, actualPid.get());
            testLock.unlock();
            assertFalse(testLock.isLockedByCurrentProcess(actualPid::set));
            assertEquals(TableStoreWriteLock.UNLOCKED, actualPid.get());
        }
    }

    @Test(timeout = 5_000)
    public void lockWillBeAcquiredAfterTimeoutWithAWarning() throws InterruptedException {
        try (final TableStoreWriteLock testLock = createTestLock(tableStore, 50)) {
            Thread t = new Thread(testLock::lock);
            t.start();
            t.join();
            testLock.lock();
            assertTrue(exceptions.keySet().stream()
                    .anyMatch(ek -> ek.level == LogLevel.WARN && ek.clazz == TableStoreWriteLock.class && ek.message.startsWith("Forced unlock")));
            expectException("Unlocking forcibly");
            expectException("Forced unlock");
        }
    }

    @Test
    public void unlockWillWarnIfNotLocked() {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            testLock.unlock();
            assertTrue(exceptions.keySet().stream()
                    .anyMatch(ek -> ek.level == LogLevel.WARN && ek.clazz == TableStoreWriteLock.class && ek.message.startsWith("Write lock was already unlocked.")));
            expectException("Write lock was already unlocked.");
        }
    }

    @Test(timeout = 5_000)
    public void unlockWillNotUnlockAndWarnIfLockedByAnotherProcess() throws IOException, InterruptedException {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            final Process process = ProcessRunner.runClass(TableStoreWriteLockLockerProcess.class, storeDirectory.toAbsolutePath().toString(), TEST_LOCK_NAME);
            while (!testLock.locked()) {
                Jvm.pause(10);
            }
            testLock.unlock();
            assertTrue(testLock.locked());
            assertTrue(exceptions.keySet().stream()
                    .anyMatch(ek -> ek.level == LogLevel.WARN && ek.clazz == TableStoreWriteLock.class && ek.message.startsWith("Write lock was locked by someone else!")));
            expectException("Write lock was locked by someone else!");
            process.destroy();
            process.waitFor();
        }
    }

    @Test(timeout = 5_000)
    public void forceUnlockWillUnlockAndWarnIfLockedByAnotherProcess() throws IOException, InterruptedException {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            final Process process = ProcessRunner.runClass(TableStoreWriteLockLockerProcess.class, storeDirectory.toAbsolutePath().toString(), TEST_LOCK_NAME);
            while (!testLock.locked()) {
                Jvm.pause(10);
            }
            testLock.forceUnlock();
            assertFalse(testLock.locked());
            assertTrue(exceptions.keySet().stream()
                    .anyMatch(ek -> ek.level == LogLevel.WARN && ek.clazz == TableStoreWriteLock.class && ek.message.startsWith("Forced unlock for the lock")));
            expectException("Forced unlock for the lock");
            process.destroy();
            process.waitFor();
        }
    }

    @Test(timeout = 5_000)
    public void forceUnlockWillNotWarnIfLockIsNotLocked() {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            testLock.forceUnlock();
            assertFalse(testLock.locked());
        }
    }

    @Test(timeout = 5_000)
    public void forceUnlockWillWarnIfLockIsLockedByCurrentProcess() {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            testLock.lock();
            testLock.forceUnlock();
            assertFalse(testLock.locked());
            assertTrue(exceptions.keySet().stream()
                    .anyMatch(ek -> ek.level == LogLevel.WARN && ek.clazz == TableStoreWriteLock.class && ek.message.startsWith("Forced unlock for the lock")));
            expectException("Forced unlock for the lock");
        }
    }

    @Test(timeout = 5_000)
    public void forceUnlockQuietlyWillUnlockWithNoWarningIfLockedByAnotherProcess() throws IOException, InterruptedException {
        try (final TableStoreWriteLock testLock = createTestLock()) {
            final Process process = ProcessRunner.runClass(TableStoreWriteLockLockerProcess.class, storeDirectory.toAbsolutePath().toString(), TEST_LOCK_NAME);
            while (!testLock.locked()) {
                Jvm.pause(10);
            }
            testLock.forceUnlockQuietly();
            assertFalse(testLock.locked());
            process.destroy();
            process.waitFor();
        }
    }

    @Test
    public void lockPreventsConcurrentAcquisition() {
        AtomicBoolean lockIsAcquired = new AtomicBoolean(false);
        try (final TableStoreWriteLock testLock = createTestLock(tableStore, 10_000)) {
            int numThreads = Math.min(6, Runtime.getRuntime().availableProcessors());
            ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            final Collection<Future<?>> futures = IntStream.range(0, numThreads)
                    .mapToObj(v -> executorService.submit(new LockAcquirer(testLock, lockIsAcquired, 30, barrier)))
                    .collect(Collectors.toList());
            futures.forEach(fut -> {
                try {
                    fut.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            Threads.shutdown(executorService);
        }
    }

    private TableStoreWriteLock createTestLock() {
        return createTestLock(tableStore, TIMEOUT_MS);
    }

    @NotNull
    private static TableStoreWriteLock createTestLock(TableStore<Metadata.NoMeta> tableStore, long timeoutMilliseconds) {
        return new TableStoreWriteLock(tableStore, Pauser::balanced, timeoutMilliseconds, TEST_LOCK_NAME);
    }

    static class LockAcquirer implements Runnable {

        private final TableStoreWriteLock tableStoreWriteLock;
        private final AtomicBoolean lockIsAcquired;
        private final int numberOfIterations;
        private final CyclicBarrier barrier;

        LockAcquirer(TableStoreWriteLock tableStoreWriteLock, AtomicBoolean lockIsAcquired, int numberOfIterations, CyclicBarrier barrier) {
            this.tableStoreWriteLock = tableStoreWriteLock;
            this.lockIsAcquired = lockIsAcquired;
            this.numberOfIterations = numberOfIterations;
            this.barrier = barrier;
        }

        @Override
        public void run() {
            try {
                barrier.await();
                for (int i = 0; i < numberOfIterations; i++) {
                    tableStoreWriteLock.lock();
                    try {
                        lockIsAcquired.compareAndSet(false, true);
                        Jvm.pause(10);
                        lockIsAcquired.compareAndSet(true, false);
                    } finally {
                        tableStoreWriteLock.unlock();
                        Jvm.pause(1);
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}