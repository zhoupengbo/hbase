/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hbase.thrift;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HServerAddress;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Scanner;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.Cell;
import org.apache.hadoop.hbase.io.RowResult;
import org.apache.hadoop.hbase.thrift.generated.AlreadyExists;
import org.apache.hadoop.hbase.thrift.generated.BatchMutation;
import org.apache.hadoop.hbase.thrift.generated.ColumnDescriptor;
import org.apache.hadoop.hbase.thrift.generated.Hbase;
import org.apache.hadoop.hbase.thrift.generated.IOError;
import org.apache.hadoop.hbase.thrift.generated.IllegalArgument;
import org.apache.hadoop.hbase.thrift.generated.Mutation;
import org.apache.hadoop.hbase.thrift.generated.TRegionInfo;
import org.apache.hadoop.hbase.thrift.generated.TCell;
import org.apache.hadoop.hbase.thrift.generated.TRowResult;
import org.apache.hadoop.hbase.util.Bytes;

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.THsHaServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;

/**
 * ThriftServer - this class starts up a Thrift server which implements the
 * Hbase API specified in the Hbase.thrift IDL file.
 */
public class ThriftServer {
  
  /**
   * The HBaseHandler is a glue object that connects Thrift RPC calls to the
   * HBase client API primarily defined in the HBaseAdmin and HTable objects.
   */
  public static class HBaseHandler implements Hbase.Iface {
    protected HBaseConfiguration conf = new HBaseConfiguration();
    protected HBaseAdmin admin = null;
    protected final Log LOG = LogFactory.getLog(this.getClass().getName());

    // nextScannerId and scannerMap are used to manage scanner state
    protected int nextScannerId = 0;
    protected HashMap<Integer, Scanner> scannerMap = null;
    
    /**
     * Returns a list of all the column families for a given htable.
     * 
     * @param table
     * @return
     * @throws IOException
     */
    byte[][] getAllColumns(HTable table) throws IOException {
      HColumnDescriptor[] cds = table.getTableDescriptor().getColumnFamilies();
      byte[][] columns = new byte[cds.length][];
      for (int i = 0; i < cds.length; i++) {
        columns[i] = cds[i].getNameWithColon();
      }
      return columns;
    }
          
    /**
     * Creates and returns an HTable instance from a given table name.
     * 
     * @param tableName
     *          name of table
     * @return HTable object
     * @throws IOException
     * @throws IOError
     */
    protected HTable getTable(final byte[] tableName) throws IOError,
        IOException {
      return new HTable(this.conf, tableName);
    }
    
    /**
     * Assigns a unique ID to the scanner and adds the mapping to an internal
     * hash-map.
     * 
     * @param scanner
     * @return integer scanner id
     */
    protected synchronized int addScanner(Scanner scanner) {
      int id = nextScannerId++;
      scannerMap.put(id, scanner);
      return id;
    }
    
    /**
     * Returns the scanner associated with the specified ID.
     * 
     * @param id
     * @return a Scanner, or null if ID was invalid.
     */
    protected synchronized Scanner getScanner(int id) {
      return scannerMap.get(id);
    }
    
    /**
     * Removes the scanner associated with the specified ID from the internal
     * id->scanner hash-map.
     * 
     * @param id
     * @return a Scanner, or null if ID was invalid.
     */
    protected synchronized Scanner removeScanner(int id) {
      return scannerMap.remove(id);
    }
    
    /**
     * Constructs an HBaseHandler object.
     * 
     * @throws MasterNotRunningException
     */
    HBaseHandler() throws MasterNotRunningException {
      conf = new HBaseConfiguration();
      admin = new HBaseAdmin(conf);
      scannerMap = new HashMap<Integer, Scanner>();
    }
    
    public void enableTable(final byte[] tableName) throws IOError {
      try{
        admin.enableTable(tableName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void disableTable(final byte[] tableName) throws IOError{
      try{
        admin.disableTable(tableName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public boolean isTableEnabled(final byte[] tableName) throws IOError {
      try {
        return HTable.isTableEnabled(tableName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void compact(byte[] tableNameOrRegionName) throws IOError {
      try{
        admin.compact(tableNameOrRegionName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }

    public void majorCompact(byte[] tableNameOrRegionName) throws IOError {
      try{
        admin.majorCompact(tableNameOrRegionName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }    
    }
    
    public List<byte[]> getTableNames() throws IOError {
      try {
        HTableDescriptor[] tables = this.admin.listTables();
        ArrayList<byte[]> list = new ArrayList<byte[]>(tables.length);
        for (int i = 0; i < tables.length; i++) {
          list.add(tables[i].getName());
        }
        return list;
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public List<TRegionInfo> getTableRegions(byte[] tableName)
    throws IOError {
      try{
        HTable table = getTable(tableName);
        Map<HRegionInfo, HServerAddress> regionsInfo = table.getRegionsInfo();
        List<TRegionInfo> regions = new ArrayList<TRegionInfo>();

        for (HRegionInfo regionInfo : regionsInfo.keySet()){
          TRegionInfo region = new TRegionInfo();
          region.startKey = regionInfo.getStartKey();
          region.endKey = regionInfo.getEndKey();
          region.id = regionInfo.getRegionId();
          region.name = regionInfo.getRegionName();
          region.version = regionInfo.getVersion();
          regions.add(region);
        }
        return regions;
      } catch (IOException e){
        throw new IOError(e.getMessage());
      }
    }
    
    public List<TCell> get(byte[] tableName, byte[] row, byte[] column)
        throws IOError {
      try {
        HTable table = getTable(tableName);
        Cell cell = table.get(row, column);
        return ThriftUtilities.cellFromHBase(cell);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public List<TCell> getVer(byte[] tableName, byte[] row,
        byte[] column, int numVersions) throws IOError {
      try {
        HTable table = getTable(tableName);
        Cell[] cells = 
          table.get(row, column, numVersions);
        return ThriftUtilities.cellFromHBase(cells);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public List<TCell> getVerTs(byte[] tableName, byte[] row,
        byte[] column, long timestamp, int numVersions) throws IOError {
      try {
        HTable table = getTable(tableName);
        Cell[] cells = table.get(row, column, timestamp, numVersions);
        return ThriftUtilities.cellFromHBase(cells);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public List<TRowResult> getRow(byte[] tableName, byte[] row)
        throws IOError {
      return getRowWithColumnsTs(tableName, row, null,
                                 HConstants.LATEST_TIMESTAMP);
    }
    
    public List<TRowResult> getRowWithColumns(byte[] tableName, byte[] row,
        List<byte[]> columns) throws IOError {
      return getRowWithColumnsTs(tableName, row, columns,
                                 HConstants.LATEST_TIMESTAMP);
    }
    
    public List<TRowResult> getRowTs(byte[] tableName, byte[] row,
        long timestamp) throws IOError {
      return getRowWithColumnsTs(tableName, row, null,
                                 timestamp);
    }
    
    public List<TRowResult> getRowWithColumnsTs(byte[] tableName, byte[] row,
        List<byte[]> columns, long timestamp) throws IOError {
      try {
        HTable table = getTable(tableName);
        if (columns == null) {
          return ThriftUtilities.rowResultFromHBase(table.getRow(row,
                                                        timestamp));
        }
        byte[][] columnArr = columns.toArray(new byte[columns.size()][]);
        return ThriftUtilities.rowResultFromHBase(table.getRow(row,
                                                      columnArr, timestamp));
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void deleteAll(byte[] tableName, byte[] row, byte[] column)
        throws IOError {
      deleteAllTs(tableName, row, column, HConstants.LATEST_TIMESTAMP);
    }
    
    public void deleteAllTs(byte[] tableName, byte[] row, byte[] column,
        long timestamp) throws IOError {
      try {
        HTable table = getTable(tableName);
        table.deleteAll(row, column, timestamp);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void deleteAllRow(byte[] tableName, byte[] row) throws IOError {
      deleteAllRowTs(tableName, row, HConstants.LATEST_TIMESTAMP);
    }
    
    public void deleteAllRowTs(byte[] tableName, byte[] row, long timestamp)
        throws IOError {
      try {
        HTable table = getTable(tableName);
        table.deleteAll(row, timestamp);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void createTable(byte[] tableName,
        List<ColumnDescriptor> columnFamilies) throws IOError,
        IllegalArgument, AlreadyExists {
      try {
        if (admin.tableExists(tableName)) {
          throw new AlreadyExists("table name already in use");
        }
        HTableDescriptor desc = new HTableDescriptor(tableName);
        for (ColumnDescriptor col : columnFamilies) {
          HColumnDescriptor colDesc = ThriftUtilities.colDescFromThrift(col);
          desc.addFamily(colDesc);
        }
        admin.createTable(desc);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgument(e.getMessage());
      }
    }
    
    public void deleteTable(byte[] tableName) throws IOError {
      if (LOG.isDebugEnabled()) {
        LOG.debug("deleteTable: table=" + new String(tableName));
      }
      try {
        if (!admin.tableExists(tableName)) {
          throw new IOError("table does not exist");
        }
        admin.deleteTable(tableName);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public void mutateRow(byte[] tableName, byte[] row,
        List<Mutation> mutations) throws IOError, IllegalArgument {
      mutateRowTs(tableName, row, mutations, HConstants.LATEST_TIMESTAMP);
    }
    
    public void mutateRowTs(byte[] tableName, byte[] row,
        List<Mutation> mutations, long timestamp) throws IOError, IllegalArgument {
      HTable table = null;
      try {
        table = getTable(tableName);
        BatchUpdate batchUpdate = new BatchUpdate(row, timestamp);
        for (Mutation m : mutations) {
          if (m.isDelete) {
            batchUpdate.delete(m.column);
          } else {
            batchUpdate.put(m.column, m.value);
          }
        }
        table.commit(batchUpdate);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgument(e.getMessage());
      }
    }
    
    public void mutateRows(byte[] tableName, List<BatchMutation> rowBatches) 
        throws IOError, IllegalArgument, TException {
      mutateRowsTs(tableName, rowBatches, HConstants.LATEST_TIMESTAMP);
    }
 
    public void mutateRowsTs(byte[] tableName, List<BatchMutation> rowBatches, long timestamp)
        throws IOError, IllegalArgument, TException {
      List<BatchUpdate> batchUpdates = new ArrayList<BatchUpdate>();
       
      for (BatchMutation batch : rowBatches) {
        byte[] row = batch.row;
        List<Mutation> mutations = batch.mutations;
        BatchUpdate batchUpdate = new BatchUpdate(row, timestamp);
        for (Mutation m : mutations) {
          if (m.isDelete) {
            batchUpdate.delete(m.column);
          } else {
            batchUpdate.put(m.column, m.value);
          }
        }
        batchUpdates.add(batchUpdate);
      }

      HTable table = null;
      try {
        table = getTable(tableName);
        table.commit(batchUpdates);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgument(e.getMessage());
      }
    }

    public long atomicIncrement(byte[] tableName, byte[] row, byte[] column, long amount) throws IOError, IllegalArgument, TException {
      HTable table;
      try {
        table = getTable(tableName);
        return table.incrementColumnValue(row, column, amount);
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }

    public void scannerClose(int id) throws IOError, IllegalArgument {
      LOG.debug("scannerClose: id=" + id);
      Scanner scanner = getScanner(id);
      if (scanner == null) {
        throw new IllegalArgument("scanner ID is invalid");
      }
      scanner.close();
      removeScanner(id);
    }
    
    public List<TRowResult> scannerGet(int id) throws IllegalArgument,
        IOError {
      LOG.debug("scannerGet: id=" + id);
      Scanner scanner = getScanner(id);
      if (scanner == null) {
        throw new IllegalArgument("scanner ID is invalid");
      }
      
      RowResult results = null;
      
      try {
        results = scanner.next();
        if (results == null) {
          return new ArrayList<TRowResult>();
        }
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
      return ThriftUtilities.rowResultFromHBase(results);
    }
    
    public int scannerOpen(byte[] tableName, byte[] startRow,
        List<byte[]> columns) throws IOError {
      try {
        HTable table = getTable(tableName);
        byte[][] columnsArray = null;
        if ((columns == null) || (columns.size() == 0)) {
          columnsArray = getAllColumns(table);
        } else {
          columnsArray = columns.toArray(new byte[0][]);
        }
        return addScanner(table.getScanner(columnsArray, startRow));
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public int scannerOpenWithStop(byte[] tableName, byte[] startRow,
        byte[] stopRow, List<byte[]> columns) throws IOError, TException {
      try {
        HTable table = getTable(tableName);
        byte[][] columnsArray = null;
        if ((columns == null) || (columns.size() == 0)) {
          columnsArray = getAllColumns(table);
        } else {
          columnsArray = columns.toArray(new byte[0][]);
        }
        return addScanner(table.getScanner(columnsArray, startRow, stopRow));
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public int scannerOpenTs(byte[] tableName, byte[] startRow,
        List<byte[]> columns, long timestamp) throws IOError, TException {
      try {
        HTable table = getTable(tableName);
        byte[][] columnsArray = null;
        if ((columns == null) || (columns.size() == 0)) {
          columnsArray = getAllColumns(table);
        } else {
          columnsArray = columns.toArray(new byte[0][]);
        }
        return addScanner(table.getScanner(columnsArray, startRow, timestamp));
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public int scannerOpenWithStopTs(byte[] tableName, byte[] startRow,
        byte[] stopRow, List<byte[]> columns, long timestamp)
        throws IOError, TException {
      try {
        HTable table = getTable(tableName);
        byte[][] columnsArray = null;
        if ((columns == null) || (columns.size() == 0)) {
          columnsArray = getAllColumns(table);
        } else {
          columnsArray = columns.toArray(new byte[0][]);
        }
        return addScanner(table.getScanner(columnsArray, startRow, stopRow,
            timestamp));
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }
    
    public Map<byte[], ColumnDescriptor> getColumnDescriptors(
        byte[] tableName) throws IOError, TException {
      try {
        TreeMap<byte[], ColumnDescriptor> columns =
          new TreeMap<byte[], ColumnDescriptor>(Bytes.BYTES_COMPARATOR);
        
        HTable table = getTable(tableName);
        HTableDescriptor desc = table.getTableDescriptor();
        
        for (HColumnDescriptor e : desc.getFamilies()) {
          ColumnDescriptor col = ThriftUtilities.colDescFromHbase(e);
          columns.put(col.name, col);
        }
        return columns;
      } catch (IOException e) {
        throw new IOError(e.getMessage());
      }
    }    
  }
  
  //
  // Main program and support routines
  //
  
  private static void printUsageAndExit() {
    printUsageAndExit(null);
  }
  
  private static void printUsageAndExit(final String message) {
    if (message != null) {
      System.err.println(message);
    }
    System.out.println("Usage: java org.apache.hadoop.hbase.thrift.ThriftServer " +
      "--help | [--port=PORT] start");
    System.out.println("Arguments:");
    System.out.println(" start Start thrift server");
    System.out.println(" stop  Stop thrift server");
    System.out.println("Options:");
    System.out.println(" port  Port to listen on. Default: 9090");
    // System.out.println(" bind  Address to bind on. Default: 0.0.0.0.");
    System.out.println(" help  Print this message and exit");
    System.exit(0);
  }

  /*
   * Start up the Thrift server.
   * @param args
   */
  protected static void doMain(final String [] args) throws Exception {
    if (args.length < 1) {
      printUsageAndExit();
    }

    int port = 9090;
    // String bindAddress = "0.0.0.0";

    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
//    final String addressArgKey = "--bind=";
    final String portArgKey = "--port=";
    for (String cmd: args) {
//      if (cmd.startsWith(addressArgKey)) {
//        bindAddress = cmd.substring(addressArgKey.length());
//        continue;
//      } else 
      if (cmd.startsWith(portArgKey)) {
        port = Integer.parseInt(cmd.substring(portArgKey.length()));
        continue;
      } else if (cmd.equals("--help") || cmd.equals("-h")) {
        printUsageAndExit();
      } else if (cmd.equals("start")) {
        continue;
      } else if (cmd.equals("stop")) {
        printUsageAndExit("To shutdown the thrift server run " +
          "bin/hbase-daemon.sh stop thrift or send a kill signal to " +
          "the thrift server pid");
      }
      
      // Print out usage if we get to here.
      printUsageAndExit();
    }
    Log LOG = LogFactory.getLog("ThriftServer");
    LOG.info("starting HBase Thrift server on port " +
      Integer.toString(port));
    HBaseHandler handler = new HBaseHandler();
    Hbase.Processor processor = new Hbase.Processor(handler);
    TNonblockingServerTransport serverTransport = 
      new TNonblockingServerSocket(port);
    TProtocolFactory protFactory = new TCompactProtocol.Factory();
    TServer server = new THsHaServer(processor, serverTransport,
      protFactory);
    server.serve();
  }
  
  /**
   * @param args
   * @throws Exception 
   */
  public static void main(String [] args) throws Exception {
    doMain(args);
  }
}
