package ru.mail.polis.dao.valaubr;

import com.google.common.collect.Iterators;
import com.google.common.collect.UnmodifiableIterator;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.Iters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class TablesPool implements Table {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final NavigableMap<Integer, Table> writingFlushTables;
    private final BlockingQueue<FlushingTable> flushQueue;
    private final long memFlushThreshold;
    private final AtomicBoolean stopFlag = new AtomicBoolean();
    private MemTable current;
    private int generation;

    public TablesPool(final long memFlushThreshold, final int startGeneration, final int flushTablePool) {
        this.memFlushThreshold = memFlushThreshold;
        this.current = new MemTable();
        this.generation = startGeneration;
        this.writingFlushTables = new ConcurrentSkipListMap<>();
        this.flushQueue = new ArrayBlockingQueue<>(flushTablePool);
    }

    @NotNull
    @Override
    public Iterator<Cell> iterator(@NotNull ByteBuffer from) throws IOException {
        final List<Iterator<Cell>> iterators;
        lock.readLock().lock();
        try {
            iterators = new ArrayList<>(writingFlushTables.size() + 1);
            iterators.add(current.iterator(from));
            for (final Table table : writingFlushTables.values()) {
                iterators.add(table.iterator(from));
            }
        } finally {
            lock.readLock().unlock();
        }
        final UnmodifiableIterator<Cell> merged = Iterators.mergeSorted(iterators, Cell.COMPARATOR);
        final Iterator<Cell> withoutEquals = Iters.collapseEquals(merged, Cell::getKey);
        return Iterators.filter(withoutEquals,
                cell -> {
                    assert cell != null;
                    return !cell.getValue().isTombstone();
                });
    }

    @Override
    public long getSizeInBytes() {
        lock.readLock().lock();
        try {
            long size = current.getSizeInBytes();
            for (final Map.Entry<Integer, Table> table : writingFlushTables.entrySet()) {
                size += table.getValue().getSizeInBytes();
            }
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    @Deprecated(since = "Do not usable", forRemoval = true)
    public int size() {
        return 0;
    }

    @Override
    public void close() {
        if (!stopFlag.compareAndSet(false, true)) {
            return;
        }
        FlushingTable flushingTable;
        lock.writeLock().lock();
        try {
            flushingTable = new FlushingTable(current, generation, true);
            writingFlushTables.put(flushingTable.getGen(), flushingTable.getTable());
        } finally {
            lock.writeLock().unlock();
        }
        try {
            flushQueue.put(flushingTable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) {
        if (stopFlag.get()) {
            throw new IllegalStateException("Already stopped");
        }
        lock.readLock().lock();
        try {
            current.upsert(key.duplicate(), value.duplicate());
        } finally {
            lock.readLock().unlock();
        }
        putIntoFlushQueue();
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) {
        if (stopFlag.get()) {
            throw new IllegalStateException("Already stopped");
        }
        lock.readLock().lock();
        try {
            current.remove(key.duplicate());
        } finally {
            lock.readLock().unlock();
        }
        putIntoFlushQueue();
    }

    FlushingTable takeToFlash() throws InterruptedException {
        return flushQueue.take();
    }

    void flushed(final int generation) {
        lock.writeLock().lock();
        try {
            writingFlushTables.remove(generation);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void putIntoFlushQueue() {
        FlushingTable tableToFlush = null;
        lock.writeLock().lock();
        try {
            if (current.getSizeInBytes() > memFlushThreshold) {
                tableToFlush = new FlushingTable(current, generation);
                writingFlushTables.put(generation, current);
                generation++;
                current = new MemTable();
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (tableToFlush != null) {
            try {
                flushQueue.put(tableToFlush);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public int getGeneration() {
        lock.readLock().lock();
        try {
            return generation;
        } finally {
            lock.readLock().unlock();
        }
    }
}