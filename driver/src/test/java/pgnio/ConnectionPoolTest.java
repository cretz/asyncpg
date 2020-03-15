package pgnio;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class ConnectionPoolTest extends DbTestBase {
  @Test
  public void testSamePidInPool() throws Exception {
    assertSameIdInPool(true);
    assertSameIdInPool(false);
  }

  protected void assertSameIdInPool(boolean eager) throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(1).poolConnectEagerly(eager))) {
      // Could use the process ID in the connection, but might as well ask for it
      int pid = pool.withConnection(this::getBackendPid).get();
      int nextPid = pool.withConnection(this::getBackendPid).get();
      Assert.assertEquals(pid, nextPid);
    }
  }

  protected CompletableFuture<Integer> getBackendPid(QueryReadyConnection.AutoCommit conn) {
    return conn.simpleQueryRows("SELECT pg_backend_pid()").thenApply(rows ->
        RowReader.DEFAULT.get(rows.get(0), 0, Integer.class));
  }

  @Test
  public void testDifferentPidAfterSelfTerminated() throws Exception {
    assertDifferentPidAfterSelfTerminated(true);
    assertDifferentPidAfterSelfTerminated(false);
  }

  protected void assertDifferentPidAfterSelfTerminated(boolean eager) throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(1).poolConnectEagerly(eager))) {
      int pid = pool.withConnection(c -> c.terminated(getBackendPid(c))).get();
      int nextPid = pool.withConnection(this::getBackendPid).get();
      Assert.assertNotEquals(pid, nextPid);
    }
  }

  @Test
  public void testDifferentPidAfterExternallyTerminated() throws Exception {
    // Stop showing warnings here
    Level oldLevel = ConnectionPool.log.getLevel();
    ConnectionPool.log.setLevel(Level.SEVERE);
    try {
      assertDifferentPidAfterExternallyTerminated(true);
      assertDifferentPidAfterExternallyTerminated(false);
    } finally {
      ConnectionPool.log.setLevel(oldLevel);
    }
  }

  protected void assertDifferentPidAfterExternallyTerminated(boolean eager) throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(1).
        poolConnectEagerly(eager).poolValidationQuery("SELECT 1"))) {
      int pid = pool.withConnection(this::getBackendPid).get();
      withConnectionSync(c -> c.simpleQueryExec("SELECT pg_terminate_backend(" + pid + ")"));
      int nextPid = pool.withConnection(this::getBackendPid).get();
      Assert.assertNotEquals(pid, nextPid);
    }
  }

  @Test
  public void testFirstLazyConnectionIsOnlyOne() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(5))) {
      long count = pool.withConnection(this::getConnectionCount).get();
      Assert.assertEquals(1L, count);
      count = pool.withConnection(this::getConnectionCount).get();
      Assert.assertEquals(1L, count);
    }
  }

  protected CompletableFuture<Long> getConnectionCount(QueryReadyConnection.AutoCommit conn) {
    return conn.simpleQueryRows("SELECT COUNT(1) FROM pg_stat_activity WHERE datname = '" +
        db.conf().dbConf.database + "'").thenApply(rows -> RowReader.DEFAULT.get(rows.get(0), 0, Long.class));
  }

  protected CompletableFuture<Void> waitOn(CountDownLatch latch) {
    return waitOn(CompletableFuture.completedFuture(null), latch);
  }

  protected <T> CompletableFuture<T> waitOn(CompletableFuture<T> fut, CountDownLatch latch) {
    return fut.whenComplete((__, ___) -> {
      try {
        latch.await();
      } catch (InterruptedException e) { throw new RuntimeException(e); }
    });
  }

  @Test
  public void testSecondLazyConnectionCreatedWhenNeeded() throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(5))) {
      CountDownLatch firstLatch = new CountDownLatch(1);
      CountDownLatch secondLatch = new CountDownLatch(1);
      CompletableFuture<Long> firstCount = new CompletableFuture<>();
      pool.withConnection(c -> {
        try {
		  firstCount.complete(getConnectionCount(c).get());
		} catch (InterruptedException | ExecutionException e) {
		  firstCount.completeExceptionally(e);
		}
        return waitOn(firstLatch);
      });
      CompletableFuture<Long> secondCount = firstCount.thenCompose(____-> pool.withConnection(c ->
          waitOn(secondLatch).thenCompose(__ -> getConnectionCount(c))));
      Assert.assertEquals(1L, firstCount.get().longValue());
      secondLatch.countDown();
      Assert.assertEquals(2L, secondCount.get().longValue());
      firstLatch.countDown();
    }
  }

  @Test
  public void testConnectionPoolAllUsedWillBlock() throws Exception {
    assertConnectionPoolAllUsedWillBlock(true);
    assertConnectionPoolAllUsedWillBlock(false);
  }

  protected void assertConnectionPoolAllUsedWillBlock(boolean eager) throws Exception {
    try (ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(2).poolConnectEagerly(eager))) {
      CountDownLatch latch = new CountDownLatch(1);
      CompletableFuture<Void> firstFut = pool.withConnection(c -> waitOn(latch));
      CompletableFuture<Void> secondFut = pool.withConnection(c -> waitOn(latch));
      // Expect a timeout
      try {
        pool.borrowConnection(100, TimeUnit.MILLISECONDS);
        Assert.fail();
      } catch (IllegalStateException e) {
        Assert.assertTrue(e.getMessage().startsWith("Timeout"));
      }
      latch.countDown();
      CompletableFuture.allOf(firstFut, secondFut).get();
    }
  }

  @Test
  public void testEagerConnectionCount() throws Exception {
    try (ConnectionPool ignored = new ConnectionPool(newDefaultConfig().poolSize(2).poolConnectEagerly(true))) {
      // Get the connection count from outside the pool
      Assert.assertEquals(3L, withConnectionSync(this::getConnectionCount).longValue());
    }
  }

  @Test
  public void testConnectionClosedWhenReturnedToClosedPool() throws Exception {
    // Create a pool, get a connection, close the pool
    ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(1));
    QueryReadyConnection.AutoCommit connOutsideClosedPool = pool.borrowConnection().get();
    pool.close();
    // The connection should still work
    Assert.assertEquals(1, RowReader.DEFAULT.get(
        connOutsideClosedPool.simpleQueryRows("SELECT 1").get().get(0), 0, Integer.class).intValue());
    // Check count (which includes the connection requesting it)
    Assert.assertEquals(2L, withConnectionSync(this::getConnectionCount).longValue());
    // Now return the connection
    try {
      pool.returnConnection(connOutsideClosedPool);
      Assert.fail();
    } catch (IllegalStateException e) {
      // Check count again to make sure closed
      Assert.assertEquals(1L, withConnectionSync(this::getConnectionCount).longValue());
    }
  }

  @Test
  public void testConnectionNotClosedWhenReturnedToClosedPool() throws Exception {
    // Create a pool, get a connection, close the pool
    ConnectionPool pool = new ConnectionPool(newDefaultConfig().poolSize(1).
        poolCloseReturnedConnectionOnClosedPool(false));
    QueryReadyConnection.AutoCommit connOutsideClosedPool = pool.borrowConnection().get();
    pool.close();
    // The connection should still work
    Assert.assertEquals(1, RowReader.DEFAULT.get(
        connOutsideClosedPool.simpleQueryRows("SELECT 1").get().get(0), 0, Integer.class).intValue());
    // Check count (which includes the connection requesting it)
    Assert.assertEquals(2L, withConnectionSync(this::getConnectionCount).longValue());
    // Now return the connection
    try {
      pool.returnConnection(connOutsideClosedPool);
      Assert.fail();
    } catch (IllegalStateException e) {
      // Check still works and count again
      Assert.assertEquals(1, RowReader.DEFAULT.get(
          connOutsideClosedPool.simpleQueryRows("SELECT 1").get().get(0), 0, Integer.class).intValue());
      Assert.assertEquals(2L, withConnectionSync(this::getConnectionCount).longValue());
    }
    connOutsideClosedPool.close();
  }
}
