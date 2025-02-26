package net.openhft.chronicle.queue.impl.single;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.BackgroundResourceReleaser;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.core.time.SetTimeProvider;
import net.openhft.chronicle.queue.*;
import net.openhft.chronicle.testframework.process.JavaProcessBuilder;
import net.openhft.chronicle.wire.DocumentContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EmptyRollCycleTest extends QueueTestCommon {

    public static final String EMPTY_ROLL_CYCLE_NAME = "19700101-0020X.cq4";
    private Path dataDirectory;

    @Before
    public void setUp() {
        dataDirectory = IOTools.createTempDirectory("EmptyRollCycleTest");
    }

    @After
    public void tearDown() {
        IOTools.deleteDirWithFiles(dataDirectory.toFile());
    }

    @Test
    public void tailerShouldTolerateEmptyRollCycleAtEnd() throws IOException {
        ignoreException("Channel closed while unlocking");
        createQueueWithEmptyRollCycleAtEnd();

        // read through the queue
        try (SingleChronicleQueue queue = ChronicleQueue.singleBuilder(dataDirectory)
                .rollCycle(RollCycles.TEN_MINUTELY)
                .timeoutMS(100)
                .build();
             ExcerptTailer tailer = queue.createTailer()) {
            int expectedValue = 0;
            while (true) {
                try (final DocumentContext readingDocument = tailer.readingDocument()) {
                    if (!readingDocument.isPresent()) {
                        break;
                    }
                    assertEquals(expectedValue++, readingDocument.wire().read("test").int32());
                }
            }
            assertEquals(2, expectedValue);
        }
    }

    @Test
    public void appenderShouldTolerateEmptyRollCycleAtEnd() throws IOException {
        ignoreException("Channel closed while unlocking");
        ignoreException("Renamed un-acquirable segment file");
        createQueueWithEmptyRollCycleAtEnd();

        long indexWritten = -1;
        // append to the queue
        try (SingleChronicleQueue queue = ChronicleQueue.singleBuilder(dataDirectory)
                .rollCycle(RollCycles.TEN_MINUTELY)
                .timeoutMS(100)
                .build();
             ExcerptAppender appender = queue.createAppender();
             DocumentContext dc = appender.writingDocument()) {
            dc.wire().write("test").text("appending");
            indexWritten = dc.index();
        }

        try (SingleChronicleQueue queue = ChronicleQueue.singleBuilder(dataDirectory)
                .rollCycle(RollCycles.TEN_MINUTELY)
                .build();
             ExcerptTailer tailer = queue.createTailer()) {
            tailer.moveToIndex(indexWritten);
            try (final DocumentContext readingDocument = tailer.readingDocument()) {
                assertTrue(readingDocument.isPresent());
                assertEquals("appending", readingDocument.wire().read("test").text());
            }
        }
    }

    @Test(timeout = 5_000)
    public void appropriateExceptionIsThrownWhenLockCannotBeAcquiredForRecovery() throws IOException, InterruptedException {
        createQueueWithEmptyRollCycleAtEnd();

        final Path emptyRollCycle = dataDirectory.resolve(EMPTY_ROLL_CYCLE_NAME);
        final Process start = JavaProcessBuilder.create(LockingProcess.class)
                .withProgramArguments(emptyRollCycle.toString())
                .start();
        try {
            waitForFileToBeLocked(emptyRollCycle);

            try (SingleChronicleQueue queue = ChronicleQueue.singleBuilder(dataDirectory)
                    .rollCycle(RollCycles.TEN_MINUTELY)
                    .timeoutMS(100)
                    .build();
                 ExcerptTailer tailer = queue.createTailer()) {
                for (int i = 0; i < 2; i++) {
                    try (final DocumentContext readingDocument = tailer.readingDocument()) {
                        assertTrue(readingDocument.isPresent());
                    }
                }
                assertEquals(false, tailer.readingDocument().isPresent());
            }
        } finally {
            start.destroy();
            assertTrue(start.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private void createQueueWithEmptyRollCycleAtEnd() throws IOException {
        SetTimeProvider timeProvider = new SetTimeProvider();
        try (SingleChronicleQueue queue = ChronicleQueue.singleBuilder(dataDirectory)
                .timeProvider(timeProvider)
                .rollCycle(RollCycles.TEN_MINUTELY)
                .build();
             ExcerptAppender appender = queue.createAppender()) {
            for (int i = 0; i < 3; i++) {
                try (final DocumentContext documentContext = appender.writingDocument()) {
                    documentContext.wire().write("test").int32(i);
                }
                timeProvider.advanceMillis(TimeUnit.MINUTES.toMillis(10));
            }
        }

        // Delete the last roll cycle
        final Path lastRollCycle = dataDirectory.resolve(EMPTY_ROLL_CYCLE_NAME);
        // This test was flaky some small percentage of time due to some lingering file handles.
        // Ensure everything is fully cleaned up before deleting the file.
        BackgroundResourceReleaser.releasePendingResources();
        Files.delete(lastRollCycle);

        // Replace it with an empty file
        Files.createFile(lastRollCycle);
    }

    private void waitForFileToBeLocked(Path path) throws IOException {
        try (final FileChannel open = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            while (true) {
                try (final FileLock fileLock = open.tryLock()) {
                    if (fileLock == null) {
                        break;
                    }
                }
            }
        }
    }

    private static class LockingProcess {

        public static void main(String[] args) throws IOException {
            String fileName = args[0];
            try (final FileChannel open = FileChannel.open(Paths.get(fileName), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                open.lock();
                while (!Thread.currentThread().isInterrupted()) {
                    Jvm.pause(1);
                }
            }
        }
    }
}
