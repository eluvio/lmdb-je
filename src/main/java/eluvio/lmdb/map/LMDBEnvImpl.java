/*
 * Copyright 2015 Eluvio (http://www.eluvio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eluvio.lmdb.map;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import eluvio.lmdb.api.Api;
import eluvio.lmdb.api.Env;

// TODO: register a shutdown hook to close the env?
class LMDBEnvImpl extends LMDBEnvInternal {
  final Env env;
  private final File path;
  private final boolean deleteOnClose;
  final boolean readOnly;
  private AtomicBoolean closed = new AtomicBoolean(false);
  private AtomicBoolean transactionsClosed = new AtomicBoolean(false);

  /**
   * This will track ReusableTxns so that we can cleanly close them when we
   * close the LMDBMap
   */
  private final Map<ReusableTxn, Boolean> allReusableTxns = Collections.synchronizedMap(new WeakHashMap<ReusableTxn, Boolean>());

  private final ThreadLocal<ReusableTxn> currentTxn = new ThreadLocal<ReusableTxn>() {
    @Override
    protected ReusableTxn initialValue() {
      ReusableTxn txn = new ReusableTxn(env);
      allReusableTxns.put(txn, Boolean.TRUE);
      return txn;
    }
  };

  public LMDBEnvImpl(File path, boolean readOnly, long mapsize) {
    this(path, readOnly, mapsize, 0 /* maxdbs */);
  }
  
  public LMDBEnvImpl(File path, boolean readOnly, long mapsize, int maxdbs) {
    this(path, readOnly, mapsize, maxdbs, LMDBEnv.DEFAULT_MAXREADERS);
  }

  public LMDBEnvImpl(File path, boolean readOnly, long mapsize, int maxdbs, int maxReaders) {
    this(path, readOnly, mapsize, maxdbs, maxReaders, 0);
  }

  public LMDBEnvImpl(File path, boolean readOnly, long mapsize, int maxdbs, int maxReaders, int flags) {
    this.readOnly = readOnly;

    final int readOnlyFlag = readOnly ? Api.MDB_RDONLY : 0;

    if (null == path) {
      // We'll use a temp file as our data file and open the
      // env with MDB_NOSUBDIR such that it uses our temp file
      // as the data file and using our temp file with "-lock"
      // appended as the lock file instead of needing a directory.
      try {
        path = File.createTempFile("lmdbmap_temp", "data.mdb");
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
      
      // We also specify MDB_NOSYNC since we don't care if or when data is
      // written to disk since this database is thrown away.
      flags = flags | Api.MDB_NOSUBDIR | Api.MDB_NOSYNC;
      deleteOnClose = true;
      
      // System.out.println("Using temp: "+path);
    } else {
      if (!path.isDirectory()) throw new IllegalArgumentException("Path must be a directory");
      deleteOnClose = false;
    }

    this.path = path;

    env = new Env();
    if (maxdbs > 0) env.setMaxDBs(maxdbs); // 0 == no named databases, > 0 == Named databases
    env.setMapSize(mapsize);
    env.setMaxReaders(maxReaders);
    env.open(path.toString(), readOnlyFlag | flags);

    // Unlink the file so that the OS will cleanup for us when the process exits
    if (deleteOnClose) deleteTempDBAndLockFile();
  }

  /**
   * Abort the current transaction for this thread
   */
  @Override
  public void abortTxn() {
    currentTxn.get().abortTxn();
  }

  protected void assertOpen() {
    if (closed.get()) throw new IllegalStateException("Database has been closed!");
  }

  protected void assertWritable() {
    if (readOnly) throw new UnsupportedOperationException("This map is marked as read-only!");
  }

  /**
   * Begin a transaction. Transactions belong to the thread that created them
   * and only apply to operations for the current thread. There can only be a
   * single transaction per thread.
   */
  @Override
  public void beginTxn() {
    beginTxn(false);
  }

  /**
   * Begin a transaction. Transactions belong to the thread that created them
   * and only apply to operations for the current thread. There can only be a
   * single transaction per thread.
   */
  @Override
  public void beginTxn(boolean readOnly) {
    currentTxn.get().beginTxn(readOnly || this.readOnly);

  }

  @Override
  public ReusableTxn detachTxnFromCurrentThread() {
    if (env.isThreadLocalTransactions()) throw new IllegalStateException("detachTxnFromCurrentThread() is only applicable when using the MDB_NOTLS flag");
    ReusableTxn txn = currentTxn.get();
    currentTxn.remove();
    allReusableTxns.remove(txn);
    return txn;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      closeTransactions();
      env.close();
    }
  }
  
  @Override
  void closeTransactions() {
    if (transactionsClosed.compareAndSet(false, true)) {
      for (ReusableTxn txn : allReusableTxns.keySet()) {
        txn.close();
      }
    }
  }

  /**
   * Commit the current transaction for this thread
   */
  @Override
  public void commitTxn() {
    currentTxn.get().commitTxn();
  }

  private void deleteTempDBAndLockFile() {
    if (deleteOnClose) {
      if (path.isFile()) path.delete();
      File lockFile = new File(path.toString() + "-lock");
      if (lockFile.isFile()) lockFile.delete();
    }
  }

  @Override
  public Env env() {
    return env;
  }

  @Override
  public boolean readOnly() {
    return readOnly;
  }

  @Override
  public LMDBTxnInternal withExistingReadOnlyTxn() {
    return currentTxn.get().withExistingReadOnlyTxn();
  }

  @Override
  public LMDBTxnInternal withExistingReadWriteTxn() {
    assertWritable();
    return currentTxn.get().withExistingReadWriteTxn();
  }
  
  @Override
  public LMDBTxnInternal withExistingTxn() {
    return currentTxn.get().withExistingTxn();
  }

  @Override
  public LMDBTxnInternal withNestedReadWriteTxn() {
    assertWritable();
    return currentTxn.get().withNestedReadWriteTxn();
  }

  @Override
  public LMDBTxnInternal withReadOnlyTxn() {
    return currentTxn.get().withReadOnlyTxn();
  }

  @Override
  public LMDBTxnInternal withReadWriteTxn() {
    assertWritable();
    return currentTxn.get().withReadWriteTxn();
  }
  
  @Override
  public void disableMetaSync() {
    env.disableMetaSync();
  }
  
  @Override
  public void enableMetaSync() {
    env.enableMetaSync();
  }
  
  @Override
  public void disableSync() {
    env.disableSync();
  }
  
  @Override
  public void enableSync() {
    env.enableSync();
  }

  @Override
  public void sync() {
    env.sync();
  }

  @Override
  public void sync(boolean force) {
    env.sync(force);
  }
}
