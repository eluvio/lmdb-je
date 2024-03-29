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

public interface LMDBEnv extends AutoCloseable {
  /**
   * By default we set mdb_env_set_mapsize to 1TB which should be large enough
   * so that we don't have to worry about it.
   * <p>
   * This is just the maximum size that the database can grow to.
   */
  long DEFAULT_MAPSIZE = 1099511627776L; // 1TB

  /**
   * The maximum numbers of readers we can have accessing the DB
   */
  int DEFAULT_MAXREADERS = 4096;
  
  /**
   * Abort the current transaction for this thread
   */
  void abortTxn();

  /**
   * Begin a transaction. Transactions belong to the thread that created them
   * and only apply to operations for the current thread. There can only be a
   * single transaction per thread.
   */
  void beginTxn();

  /**
   * Begin a transaction. Transactions belong to the thread that created them
   * and only apply to operations for the current thread. There can only be a
   * single transaction per thread.
   * 
   * @param readOnly should this transaction be read-only?
   */
  void beginTxn(boolean readOnly);

  /**
   * Close the underlying LMDB Environment
   */
  void close();

  /**
   * Commit the current transaction for this thread
   */
  void commitTxn();

  /**
   * If the MDB_NOTLS flag has been set then this method can be called to detach the current ReusableTxn from the
   * current thread. The caller is then responsible for making sure the ReusableTxn is properly closed.
   *
   * This was added to provide minimal support for the MDB_NOTLS flag being set. The specific use case is where we have
   * a read only transaction that has read some data from LMDB and we need to ensure that the returned Direct ByteBuffer
   * remains valid while we are processing an HTTP Request in a non-blocking Netty based environment where a single
   * request might bounce around various threads. In that case we cannot simply use a try-with-resources approach.
   *
   * Currently, there is no way for us to re-attach a transaction to the current thread and there is also no support
   * for being able to specify that we want a detached ReusableTxn instance to be used for specific read (or write)
   * calls to LMDB. That is not currently required for the specific use case this is needed for, but I could imagine
   * some sort of way to safely reattach the ReusableTxn to the current thread so that it is automatically used for
   * any read/write LMDB calls. Perhaps we save and restore any existing ReusableTxn already attached to the thread.
   *
   * @return The ReusableTxn instance that has been detached from the current Thread
   */
  ReusableTxn detachTxnFromCurrentThread();

  /**
   * Is this a read-only environment?
   * 
   * @return true if this environment is read-only
   */
  boolean readOnly();

  LMDBTxn withExistingReadOnlyTxn();

  LMDBTxn withExistingReadWriteTxn();

  LMDBTxn withExistingTxn();

  LMDBTxn withNestedReadWriteTxn();

  LMDBTxn withReadOnlyTxn();
  
  LMDBTxn withReadWriteTxn();
  
  /**
   * Enable the MDB_NOMETASYNC flag
   */
  void disableMetaSync();
  
  /**
   * Disable the MDB_NOMETASYNC flag
   * 
   * Flush system buffers to disk only once per transaction, omit the metadata flush. Defer 
   * that until the system flushes files to disk, or next non-MDB_RDONLY commit or mdb_env_sync(). 
   * This optimization maintains database integrity, but a system crash may undo the last 
   * committed transaction. I.e. it preserves the ACI (atomicity, consistency, isolation) but 
   * not D (durability) database property.
   */
  void enableMetaSync();
  
  /**
   * Enable the MDB_NOSYNC flag
   * 
   * Don't flush system buffers to disk when committing a transaction. This optimization means a
   * system crash can corrupt the database or lose the last transactions if buffers are not yet 
   * flushed to disk. The risk is governed by how often the system flushes dirty buffers to disk 
   * and how often mdb_env_sync() is called. However, if the filesystem preserves write order and 
   * the MDB_WRITEMAP flag is not used, transactions exhibit ACI (atomicity, consistency, 
   * isolation) properties and only lose D (durability). I.e. database integrity is maintained, 
   * but a system crash may undo the final transactions.
   */
  void disableSync();
  
  /**
   * Disable the MDB_NOSYNC flag
   */
  void enableSync();

  /**
   * Flush the data buffers to disk.
   *
   * Note: If MDB_NOSYNC is set then use force(true)
   */
  void sync();

  /**
   * Flush the data buffers to disk.
   *
   * @param force If non-zero, force a synchronous flush. Otherwise if the environment has the MDB_NOSYNC flag set the
   *              flushes will be omitted, and with MDB_MAPASYNC they will be asynchronous.
   */
  void sync(boolean force);
}
