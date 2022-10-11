/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.datasource;

import com.google.common.collect.ImmutableSet;
import org.awaitility.Awaitility;
import org.ethereum.TestUtils;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.util.ByteUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.ethereum.TestUtils.assertThrows;
import static org.ethereum.TestUtils.randomBytes;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.*;

class RocksDbDataSourceTest {

    @TempDir
    public Path databaseDir;

    private RocksDbDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new RocksDbDataSource("test", databaseDir.toString());
        dataSource.init();
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    @Test
    void updateBatch() {
        final int batchSize = 100;
        Map<ByteArrayWrapper, byte[]> batch = createBatch(batchSize);

        byte[] keyToDelete1 = randomBytes(32);
        byte[] keyToDelete2 = randomBytes(32);
        dataSource.put(keyToDelete1, randomBytes(32));
        assertNotNull(dataSource.get(keyToDelete1));
        dataSource.put(keyToDelete2, randomBytes(32));
        assertNotNull(dataSource.get(keyToDelete2));

        Set<ByteArrayWrapper> deleteKeys = ImmutableSet.of(ByteUtil.wrap(keyToDelete1), ByteUtil.wrap(keyToDelete2));
        dataSource.updateBatch(batch, deleteKeys);

        assertEquals(batchSize, dataSource.keys().size());
        assertNull(dataSource.get(keyToDelete1));
        assertNull(dataSource.get(keyToDelete2));

        try (DataSourceKeyIterator iterator = dataSource.keyIterator()){
            assertTrue(iterator.hasNext());
            assertNotNull(iterator.next());
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    void updateBatchNullKey() {
        Map<ByteArrayWrapper, byte[]> batch = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            batch.put(ByteUtil.wrap(randomBytes(32)), randomBytes(32));
        }
        batch.put(null, randomBytes(32));

        Set<ByteArrayWrapper> deleteKeys = Collections.emptySet();
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> dataSource.updateBatch(batch, deleteKeys));
        Assertions.assertEquals("Cannot update null values", iae.getMessage());
    }

    @Test
    void updateBatchNullValue() {
        Map<ByteArrayWrapper, byte[]> batch = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            batch.put(ByteUtil.wrap(randomBytes(32)), randomBytes(32));
        }
        batch.put(ByteUtil.wrap(randomBytes(32)), null);

        Set<ByteArrayWrapper> deleteKeys = Collections.emptySet();
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> dataSource.updateBatch(batch, deleteKeys));
        assertEquals("Cannot update null values", iae.getMessage());
    }

    @Test
    void updateBatchRetriesOnPutError() {
        RocksDB db = Mockito.mock(RocksDB.class);
        TestUtils.setInternalState(dataSource, "db", db);

        String dbExceptionMessage = "fake DBException";

        final int batchSize = 100;
        Map<ByteArrayWrapper, byte[]> batch = createBatch(batchSize);

        Set<ByteArrayWrapper> deleteKeys = Collections.emptySet();

        try (MockedConstruction<WriteBatch> writeBatchMockedConstruction = Mockito.mockConstruction(WriteBatch.class,
                (mock, context) -> doThrow(new RocksDBException(dbExceptionMessage)).when(mock).put(any(byte[].class), any(byte[].class))
        )) {
            RuntimeException updateException = assertThrows(RuntimeException.class, () -> dataSource.updateBatch(batch, deleteKeys));
            assertEquals(RocksDBException.class.getName() + ": " + dbExceptionMessage, updateException.getMessage());

            WriteBatch writeBatch1 = writeBatchMockedConstruction.constructed().get(1);
            verify(writeBatch1, times(1)).put(any(byte[].class), any(byte[].class));
            WriteBatch writeBatch2 = writeBatchMockedConstruction.constructed().get(1);
            verify(writeBatch2, times(1)).put(any(byte[].class), any(byte[].class));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void updateBatchRetriesOnDeleteError() {
        RocksDB db = Mockito.mock(RocksDB.class);
        TestUtils.setInternalState(dataSource, "db", db);

        String dbExceptionMessage = "fake DBException";

        final int batchSize = 100;
        Map<ByteArrayWrapper, byte[]> batch = createBatch(batchSize);

        byte[] keyToDelete1 = randomBytes(32);
        byte[] keyToDelete2 = randomBytes(32);
        Set<ByteArrayWrapper> deleteKeys = ImmutableSet.of(ByteUtil.wrap(keyToDelete1), ByteUtil.wrap(keyToDelete2));

        try (MockedConstruction<WriteBatch> writeBatchMockedConstruction = Mockito.mockConstruction(WriteBatch.class,
                (mock, context) -> doThrow(new RocksDBException(dbExceptionMessage)).when(mock).delete(any())
        )) {
            RuntimeException updateException = assertThrows(RuntimeException.class, () -> dataSource.updateBatch(batch, deleteKeys));
            assertEquals(RocksDBException.class.getName() + ": " + dbExceptionMessage, updateException.getMessage());

            WriteBatch writeBatch1 = writeBatchMockedConstruction.constructed().get(1);
            verify(writeBatch1, times(1)).delete(any());
            WriteBatch writeBatch2 = writeBatchMockedConstruction.constructed().get(1);
            verify(writeBatch2, times(1)).delete(any());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void put() {
        byte[] key = randomBytes(32);
        dataSource.put(key, randomBytes(32));

        assertNotNull(dataSource.get(key));
        assertEquals(1, dataSource.keys().size());
    }

    @Test
    void putNullKey() {
        byte[] value = randomBytes(32);
        assertThrows(NullPointerException.class, () -> dataSource.put(null, value));
    }

    @Test
    void putNullValue() {
        byte[] key = randomBytes(32);
        assertThrows(NullPointerException.class, () -> dataSource.put(key, null));
    }

    @Test
    void putLockWorks() {
        ReentrantReadWriteLock lock = TestUtils.getInternalState(dataSource, "resetDbLock");
        lock.writeLock().lock(); // we test write-locking because readLock() would allow multiple "read" access
        boolean unlocked = false;

        try {
            byte[] key = randomBytes(32);
            byte[] initialValue = randomBytes(32);
            byte[] updatedValue = randomBytes(32);

            AtomicBoolean threadStarted = new AtomicBoolean(false);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(new Thread(() -> {
                threadStarted.set(true);
                dataSource.put(key, updatedValue);
            }));

            // wait for thread to be started and put a new value on thread holding the write lock
            Awaitility.await().timeout(Duration.ofMillis(100)).pollDelay(Duration.ofMillis(10)).untilAtomic(threadStarted, equalTo(true));
            assertNull(dataSource.get(key)); // thread put should have not been executed during lock
            dataSource.put(key, initialValue);
            assertArrayEquals(initialValue, dataSource.get(key)); // thread put should have not been executed during write lock

            lock.writeLock().unlock();
            unlocked = true;

            future.get(500, TimeUnit.MILLISECONDS); // would throw assertion errors in thread if any
            assertArrayEquals(updatedValue, dataSource.get(key)); // thread put should prevail as last one being run
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            fail(e.getMessage());
        } finally {
            if (!unlocked) {
                lock.writeLock().unlock();
            }
        }
    }

    @Test
    void getAfterMiss() {
        byte[] key = randomBytes(32);
        Assertions.assertNull(dataSource.get(key));

        byte[] value = randomBytes(32);
        dataSource.put(key, value);
        Assertions.assertArrayEquals(dataSource.get(key), value);

        dataSource.flush();
        Assertions.assertArrayEquals(dataSource.get(key), value);
    }

    @Test
    void getAfterUpdate() {
        byte[] key = randomBytes(32);
        byte[] value = randomBytes(32);

        dataSource.put(key, value);
        Assertions.assertArrayEquals(dataSource.get(key), value);

        byte[] newValue = randomBytes(32);
        dataSource.put(key, newValue);
        Assertions.assertArrayEquals(dataSource.get(key), newValue);

        dataSource.flush();
        Assertions.assertArrayEquals(dataSource.get(key), newValue);
    }

    @Test
    void getAfterDelete() {
        byte[] key = randomBytes(32);
        byte[] value = randomBytes(32);

        dataSource.put(key, value);
        Assertions.assertArrayEquals(dataSource.get(key), value);

        dataSource.delete(key);
        Assertions.assertNull(dataSource.get(key));

        dataSource.flush();
        Assertions.assertNull(dataSource.get(key));
    }

    @Test
    void getWithException() throws RocksDBException {
        RocksDB db = Mockito.mock(RocksDB.class);
        TestUtils.setInternalState(dataSource, "db", db);

        byte[] key = TestUtils.randomBytes(20);
        RocksDBException fakeException = new RocksDBException("fake exception");
        Mockito.when(db.get(key)).thenThrow(fakeException);
        Assertions.assertThrows(RuntimeException.class, () -> dataSource.get(key));
        Mockito.verify(db, Mockito.times(2)).get(key);
    }

    @Test
    void getLockWorks() {
        ReentrantReadWriteLock lock = TestUtils.getInternalState(dataSource, "resetDbLock");
        lock.writeLock().lock(); // we test write-locking because readLock() would allow multiple "read" access
        boolean unlocked = false;

        try {
            byte[] key = TestUtils.randomBytes(20);
            byte[] value = TestUtils.randomBytes(20);

            AtomicBoolean threadStarted = new AtomicBoolean(false);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(new Thread(() -> {
                threadStarted.set(true);
                byte[] readValue = dataSource.get(key); // should be locked
                Assertions.assertArrayEquals(value, readValue); // should read value put during lock
            }));

            // wait for thread to be started and put a value during active lock for thread
            Awaitility.await().timeout(Duration.ofMillis(100)).pollDelay(Duration.ofMillis(10)).untilAtomic(threadStarted, equalTo(true));
            dataSource.put(key, value); // put value during write lock
            lock.writeLock().unlock(); // release write lock, so future can start read
            unlocked = true;

            future.get(500, TimeUnit.MILLISECONDS); // would throw assertion errors in thread if any
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Assertions.fail(e.getMessage());
        } finally {
            if (!unlocked) {
                lock.readLock().unlock();
            }
        }
    }

    @Test
    void delete() {
        byte[] key1 = TestUtils.randomBytes(20);
        byte[] value1 = TestUtils.randomBytes(20);
        byte[] key2 = TestUtils.randomBytes(20);
        byte[] value2 = TestUtils.randomBytes(20);
        dataSource.put(key1, value1);
        dataSource.put(key2, value2);

        dataSource.delete(key2);
        Assertions.assertNull(dataSource.get(key2));
        Assertions.assertNotNull(dataSource.get(key1));
    }

    @Test
    void deleteLockWorks() {
        ReentrantReadWriteLock lock = TestUtils.getInternalState(dataSource, "resetDbLock");
        lock.writeLock().lock(); // we test write-locking because readLock() would allow multiple "read" access
        boolean unlocked = false;

        try {
            byte[] key = randomBytes(32);
            byte[] value = randomBytes(32);

            AtomicBoolean threadStarted = new AtomicBoolean(false);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(new Thread(() -> {
                threadStarted.set(true);
                Assertions.assertNotNull(dataSource.get(key));
                dataSource.delete(key);
                Assertions.assertNull(dataSource.get(key));
            }));

            // wait for thread to be started and put a new value on thread holding the write lock
            Awaitility.await().timeout(Duration.ofMillis(100)).pollDelay(Duration.ofMillis(10)).untilAtomic(threadStarted, equalTo(true));
            Assertions.assertNull(dataSource.get(key)); // thread put should have not been executed during lock
            dataSource.put(key, value);
            Assertions.assertArrayEquals(value, dataSource.get(key)); // thread put should have not been executed during write lock

            Assertions.assertNotNull(dataSource.get(key));
            lock.writeLock().unlock();
            unlocked = true;

            future.get(500, TimeUnit.MILLISECONDS); // would throw assertion errors in thread if any
            Assertions.assertNull(dataSource.get(key)); // thread put should prevail as last one being run
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Assertions.fail(e.getMessage());
        } finally {
            if (!unlocked) {
                lock.writeLock().unlock();
            }
        }
    }

    private static Map<ByteArrayWrapper, byte[]> createBatch(int batchSize) {
        HashMap<ByteArrayWrapper, byte[]> result = new HashMap<>();
        for (int i = 0; i < batchSize; i++) {
            result.put(ByteUtil.wrap(randomBytes(32)), randomBytes(32));
        }
        return result;
    }

}
