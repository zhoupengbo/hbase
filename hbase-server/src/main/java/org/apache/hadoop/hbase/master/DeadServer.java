/**
 *
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
package org.apache.hadoop.hbase.master;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class to hold dead servers list and utility querying dead server list.
 */
@InterfaceAudience.Private
public class DeadServer {
  /**
   * Set of known dead servers.  On znode expiration, servers are added here.
   * This is needed in case of a network partitioning where the server's lease
   * expires, but the server is still running. After the network is healed,
   * and it's server logs are recovered, it will be told to call server startup
   * because by then, its regions have probably been reassigned.
   */
  private final Map<ServerName, Long> deadServers = new HashMap<ServerName, Long>();

  /**
   * Number of dead servers currently being processed
   */
  private int numProcessing = 0;

  /**
   * A dead server that comes back alive has a different start code. The new start code should be
   *  greater than the old one, but we don't take this into account in this method.
   *
   * @param newServerName Servername as either <code>host:port</code> or
   *                      <code>host,port,startcode</code>.
   * @return true if this server was dead before and coming back alive again
   */
  public synchronized boolean cleanPreviousInstance(final ServerName newServerName) {
    Iterator<ServerName> it = deadServers.keySet().iterator();
    while (it.hasNext()) {
      ServerName sn = it.next();
      if (ServerName.isSameHostnameAndPort(sn, newServerName)) {
        it.remove();
        return true;
      }
    }

    return false;
  }

  /**
   * @param serverName
   * @return true if this server is on the dead servers list.
   */
  public synchronized boolean isDeadServer(final ServerName serverName) {
    return deadServers.containsKey(serverName);
  }

  /**
   * Checks if there are currently any dead servers being processed by the
   * master.  Returns true if at least one region server is currently being
   * processed as dead.
   *
   * @return true if any RS are being processed as dead
   */
  public synchronized boolean areDeadServersInProgress() {
    return numProcessing != 0;
  }

  public synchronized Set<ServerName> copyServerNames() {
    Set<ServerName> clone = new HashSet<ServerName>(deadServers.size());
    clone.addAll(deadServers.keySet());
    return clone;
  }

  /**
   * Adds the server to the dead server list if it's not there already.
   * @param sn the server name
   */
  public synchronized void add(ServerName sn) {
    this.numProcessing++;
    if (!deadServers.containsKey(sn)){
      deadServers.put(sn, EnvironmentEdgeManager.currentTimeMillis());
    }
  }

  @SuppressWarnings("UnusedParameters")
  public synchronized void finish(ServerName ignored) {
    this.numProcessing--;
  }

  public synchronized int size() {
    return deadServers.size();
  }

  public synchronized boolean isEmpty() {
    return deadServers.isEmpty();
  }

  public synchronized void cleanAllPreviousInstances(final ServerName newServerName) {
    Iterator<ServerName> it = deadServers.keySet().iterator();
    while (it.hasNext()) {
      ServerName sn = it.next();
      if (ServerName.isSameHostnameAndPort(sn, newServerName)) {
        it.remove();
      }
    }
  }

  public synchronized String toString() {
    StringBuilder sb = new StringBuilder();
    for (ServerName sn : deadServers.keySet()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(sn.toString());
    }
    return sb.toString();
  }

  /**
   * Extract all the servers dead since a given time, and sort them.
   * @param ts the time, 0 for all
   * @return a sorted array list, by death time, lowest values first.
   */
  public synchronized List<Pair<ServerName, Long>> copyDeadServersSince(long ts){
    List<Pair<ServerName, Long>> res =  new ArrayList<Pair<ServerName, Long>>(size());

    for (Map.Entry<ServerName, Long> entry:deadServers.entrySet()){
      if (entry.getValue() >= ts){
        res.add(new Pair<ServerName, Long>(entry.getKey(), entry.getValue()));
      }
    }

    Collections.sort(res, ServerNameDeathDateComparator);
    return res;
  }

  private static Comparator<Pair<ServerName, Long>> ServerNameDeathDateComparator =
      new Comparator<Pair<ServerName, Long>>(){

    @Override
    public int compare(Pair<ServerName, Long> o1, Pair<ServerName, Long> o2) {
      return o1.getSecond().compareTo(o2.getSecond());
    }
  };
}
