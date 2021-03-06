/// Copyright 2017 Pinterest Inc.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
/// http://www.apache.org/licenses/LICENSE-2.0

/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.

//
// @author bol (bol@pinterest.com)
//

package com.pinterest.rocksplicator;

import com.pinterest.rocksdb_admin.thrift.AddDBRequest;
import com.pinterest.rocksdb_admin.thrift.Admin;
import com.pinterest.rocksdb_admin.thrift.AdminErrorCode;
import com.pinterest.rocksdb_admin.thrift.AdminException;
import com.pinterest.rocksdb_admin.thrift.BackupDBRequest;
import com.pinterest.rocksdb_admin.thrift.BackupDBToS3Request;
import com.pinterest.rocksdb_admin.thrift.ChangeDBRoleAndUpstreamRequest;
import com.pinterest.rocksdb_admin.thrift.CheckDBRequest;
import com.pinterest.rocksdb_admin.thrift.CheckDBResponse;
import com.pinterest.rocksdb_admin.thrift.ClearDBRequest;
import com.pinterest.rocksdb_admin.thrift.CloseDBRequest;
import com.pinterest.rocksdb_admin.thrift.GetSequenceNumberRequest;
import com.pinterest.rocksdb_admin.thrift.GetSequenceNumberResponse;
import com.pinterest.rocksdb_admin.thrift.RestoreDBRequest;
import com.pinterest.rocksdb_admin.thrift.RestoreDBFromS3Request;
import com.pinterest.rocksdb_admin.thrift.CompactDBRequest;

import org.apache.helix.model.Message;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {

  private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

  /**
   * Build a thrift client to local adminPort
   * @param adminPort
   * @return a client object
   * @throws TTransportException
   */
  public static Admin.Client getLocalAdminClient(int adminPort) throws TTransportException {
    return getAdminClient("localhost", adminPort);
  }

  /**
   * Build a thrift client to host:adminPort
   * @param host
   * @param adminPort
   * @return a client object
   * @throws TTransportException
   */
  public static Admin.Client getAdminClient(String host, int adminPort) throws TTransportException {
    TSocket sock = new TSocket(host, adminPort);
    sock.open();
    return new Admin.Client(new TBinaryProtocol(sock));
  }

  /**
   * Convert a partition name into DB name.
   * @param partitionName  e.g. "p2p1_1"
   * @return e.g. "p2p100001"
   */
  public static String getDbName(String partitionName) {
    int lastIdx = partitionName.lastIndexOf('_');
    return String.format("%s%05d", partitionName.substring(0, lastIdx),
        Integer.parseInt(partitionName.substring(lastIdx + 1)));
  }

  /**
   * Clear the content of a DB and leave it as closed.
   * @param dbName
   * @param adminPort
   */
  public static void clearDB(String dbName, int adminPort) {
    try {
      LOG.error("Clear local DB: " + dbName);
      Admin.Client client = getLocalAdminClient(adminPort);
      ClearDBRequest req = new ClearDBRequest(dbName);
      req.setReopen_db(false);
      client.clearDB(req);
    } catch (AdminException e) {
      LOG.error("Failed to destroy DB", e);
    } catch (TTransportException e) {
      LOG.error("Failed to connect to local Admin port", e);
    } catch (TException e) {
      LOG.error("ClearDB() request failed", e);
    }
  }

  /**
   * Close a DB
   * @param dbName
   * @param adminPort
   */
  public static void closeDB(String dbName, int adminPort) {
    try {
      closeRemoteOrLocalDB("localhost", adminPort, dbName);
    } catch (RuntimeException e) {
      LOG.error("closeDB failed with exception", e);
    }
  }

  public static void closeRemoteOrLocalDB(String host, int adminPort, String dbName)
      throws RuntimeException {
    try {
      LOG.error("Close DB: " + dbName + " on host: " + host);
      Admin.Client client = getAdminClient(host, adminPort);
      CloseDBRequest req = new CloseDBRequest(dbName);
      client.closeDB(req);
    } catch (AdminException e) {
      LOG.error(dbName + " doesn't exist", e);
      throw new RuntimeException(e);
    } catch (TTransportException e) {
      LOG.error("Failed to connect to Admin port", e);
      throw new RuntimeException(e);
    } catch (TException e) {
      LOG.error("CloseDB() request failed", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Add a db with a specific role.
   * @param dbName
   * @param adminPort
   * @param dbRole one of SLAVE or NOOP
   */
  public static void addDB(String dbName, int adminPort, String dbRole) throws RuntimeException {
    if ((dbRole != "SLAVE") && (dbRole != "NOOP")) {
      throw new RuntimeException("Invalid db role requested for new db " + dbName + " : " + dbRole);
    }
    LOG.error("Add local DB: " + dbName + " with role " + dbRole);
    Admin.Client client = null;
    AddDBRequest req = null;
    try {
      try {
        client = getLocalAdminClient(adminPort);
        req = new AddDBRequest(dbName, "127.0.0.1");
        req.setDb_role(dbRole);
        client.addDB(req);
      } catch (AdminException e) {
        if (e.errorCode == AdminErrorCode.DB_EXIST) {
          LOG.error(dbName + " already exists");
          return;
        }

        LOG.error("Failed to open " + dbName, e);
        if (e.errorCode == AdminErrorCode.DB_ERROR) {
          LOG.error("Trying to overwrite open " + dbName);
          req.setOverwrite(true);
          client.addDB(req);
        }
      }
    } catch (TTransportException e) {
      LOG.error("Failed to connect to local Admin port", e);
      throw new RuntimeException(e);
    } catch (TException e) {
      LOG.error("AddDB() request failed", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Add a DB as a Slave, and set it upstream to be itself. Do nothing if the DB already exists
   * @param dbName
   * @param adminPort
   */
  public static void addDB(String dbName, int adminPort) {
    try {
      addDB(dbName, adminPort, "SLAVE");
    } catch (RuntimeException e) {
      LOG.error("addDB failed with exception", e);
    }
  }

  /**
   * Log transition meesage
   * @param message
   */
  public static void logTransitionMessage(Message message) {
    LOG.error("Transition Started from " + message.getFromState() + " to " + message.getToState()
        + " for " + message.getPartitionName());
  }

  public static void logTransitionCompletionMessage(Message message) {
    LOG.error("Transition Completed from " + message.getFromState() + " to " + message.getToState()
        + " for " + message.getPartitionName());
  }

  /**
   * Get the latest sequence number of the local DB
   * @param dbName
   * @return the latest sequence number
   * @throws RuntimeException
   */
  public static long getLocalLatestSequenceNumber(String dbName, int adminPort)
      throws RuntimeException {
    LOG.error("Get local seq number");
    long seqNum = getLatestSequenceNumber(dbName, "localhost", adminPort);
    if (seqNum == -1) {
      throw new RuntimeException("Failed to fetch local sequence number for DB: " + dbName);
    }
    LOG.error("Local seq number: " + String.valueOf(seqNum));
    return seqNum;
  }

  /**
   * Get the latest sequence number of the DB on the host
   * @param dbName
   * @return the latest sequence number, -1 if fails to get it
   */
  public static long getLatestSequenceNumber(String dbName, String host, int adminPort) {
    LOG.error("Get seq number from " + host + " for " + dbName);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      GetSequenceNumberRequest request = new GetSequenceNumberRequest(dbName);
      GetSequenceNumberResponse response = client.getSequenceNumber(request);
      LOG.error(
          "Seq number for " + dbName + " on " + host + ": " + String.valueOf(response.seq_num));
      return response.seq_num;
    } catch (TException e) {
      LOG.error("Failed to get sequence number", e);
      return -1;
    }
  }

  /**
   * Change DB role and upstream on host:adminPort
   * @param host
   * @param adminPort
   * @param dbName
   * @param role
   * @param upstreamIP
   * @param upstreamPort
   * @throws RuntimeException
   */
  public static void changeDBRoleAndUpStream(
      String host, int adminPort, String dbName, String role, String upstreamIP, int upstreamPort)
      throws RuntimeException {
    LOG.error("Change " + dbName + " on " + host + " to " + role + " with upstream " + upstreamIP);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      ChangeDBRoleAndUpstreamRequest request = new ChangeDBRoleAndUpstreamRequest(dbName, role);
      request.setUpstream_ip(upstreamIP);
      request.setUpstream_port((short) upstreamPort);
      client.changeDBRoleAndUpStream(request);
    } catch (TException e) {
      LOG.error("Failed to changeDBRoleAndUpStream", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Check the status of a local DB
   * @param dbName
   * @param adminPort
   * @return the DB status
   * @throws RuntimeException
   */
  public static CheckDBResponse checkLocalDB(String dbName, int adminPort) throws RuntimeException {
    try {
      Admin.Client client = getLocalAdminClient(adminPort);

      CheckDBRequest req = new CheckDBRequest(dbName);
      return client.checkDB(req);
    } catch (TException e) {
      LOG.error("Failed to check DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Backup the DB on the host
   * @param host
   * @param adminPort
   * @param dbName
   * @param hdfsPath
   * @throws RuntimeException
   */
  public static void backupDB(String host, int adminPort, String dbName, String hdfsPath)
      throws RuntimeException {
    LOG.error("(HDFS)Backup " + dbName + " from " + host + " to " + hdfsPath);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      BackupDBRequest req = new BackupDBRequest(dbName, hdfsPath);
      client.backupDB(req);
    } catch (TException e) {
      LOG.error("Failed to backup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  public static void backupDBWithLimit(String host, int adminPort, String dbName, String hdfsPath,
                                       int limitMbs, boolean shareFilesWithChecksum)
      throws RuntimeException {
    LOG.error("Backup " + dbName + " from " + host + " to " + hdfsPath);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      BackupDBRequest req = new BackupDBRequest(dbName, hdfsPath);
      req.setLimit_mbs(limitMbs);
      req.setShare_files_with_checksum(shareFilesWithChecksum);
      client.backupDB(req);
    } catch (TException e) {
      LOG.error("Failed to backup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Restore the local DB from HDFS
   * @param adminPort
   * @param dbName
   * @param hdfsPath
   * @throws RuntimeException
   */
  public static void restoreLocalDB(int adminPort, String dbName, String hdfsPath,
                                    String upsreamHost, int upstreamPort)
      throws RuntimeException {
    restoreRemoteOrLocalDB("localhost", adminPort, dbName, hdfsPath, upsreamHost, upstreamPort);
  }

  public static void restoreRemoteOrLocalDB(String host, int adminPort, String dbName,
                                            String hdfsPath, String upsreamHost, int upstreamPort)
      throws RuntimeException {
    LOG.error("(HDFS)Restore " + dbName + " from " + hdfsPath + " with upstream " + upsreamHost);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      RestoreDBRequest req =
          new RestoreDBRequest(dbName, hdfsPath, upsreamHost, (short) upstreamPort);
      client.restoreDB(req);
    } catch (TException e) {
      LOG.error("Failed to restore DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Backup the DB on the host to S3
   * @param host
   * @param adminPort
   * @param dbName
   * @param s3Bucket
   * @param s3Path
   * @throws RuntimeException
   */
  public static void backupDBToS3(String host, int adminPort, String dbName, String s3Bucket,
                                  String s3Path)
      throws RuntimeException {
    LOG.error("(S3)Backup " + dbName + " from " + host + " to " + s3Path);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      BackupDBToS3Request req = new BackupDBToS3Request(dbName, s3Bucket, s3Path);
      client.backupDBToS3(req);
    } catch (TException e) {
      LOG.error("Failed to backup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  public static void backupDBToS3WithLimit(String host, int adminPort, String dbName, int limitMbs,
                                           String s3Bucket, String s3Path)
      throws RuntimeException {
    LOG.error("(S3)Backup " + dbName + " from " + host + " to " + s3Path);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      BackupDBToS3Request req = new BackupDBToS3Request(dbName, s3Bucket, s3Path);
      req.setLimit_mbs(limitMbs);
      client.backupDBToS3(req);
    } catch (TException e) {
      LOG.error("Failed to backup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Restore the local DB from s3
   * @param adminPort
   * @param dbName
   * @param s3Bucket
   * @param s3Path
   * @throws RuntimeException
   */
  public static void restoreLocalDBFromS3(int adminPort, String dbName, String s3Bucket,
                                          String s3Path, String upsreamHost, int upstreamPort)
      throws RuntimeException {
    restoreRemoteOrLocalDBFromS3("localhost", adminPort, dbName, s3Bucket, s3Path, upsreamHost,
        upstreamPort);
  }

  public static void restoreRemoteOrLocalDBFromS3(String host, int adminPort, String dbName,
                                                  String s3Bucket, String s3Path,
                                                  String upsreamHost, int upstreamPort)
      throws RuntimeException {
    LOG.error("(S3)Restore " + dbName + " from " + s3Path + " with upstream " + upsreamHost);
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      RestoreDBFromS3Request req =
          new RestoreDBFromS3Request(dbName, s3Bucket, s3Path, upsreamHost, (short) upstreamPort);
      client.restoreDBFromS3(req);
    } catch (TException e) {
      LOG.error("Failed to restore DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  public static void compactDB(int adminPort, String dbName) throws RuntimeException {
    LOG.error(String.format("Compact partition: %s", dbName));
    try {
      Admin.Client client = getLocalAdminClient(adminPort);
      CompactDBRequest req = new CompactDBRequest(dbName);
      client.compactDB(req);
    } catch (TException e) {
      LOG.error("Failed to dedup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Check if the DB on host:adminPort is Master. If the CheckDBRequest request fails, return false.
   * @param host
   * @param adminPort
   * @param dbName
   * @return
   */
  public static boolean isMasterReplica(String host, int adminPort, String dbName) {
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      CheckDBRequest req = new CheckDBRequest(dbName);
      CheckDBResponse res = client.checkDB(req);
      return res.is_master;
    } catch (TException e) {
      LOG.error("Failed to check DB: ", e.toString());
      return false;
    }
  }

  public static void checkSanity(String fromState, String toState, Message message,
                                 String resourceName, String partitionName) {
    if (fromState.equalsIgnoreCase(message.getFromState())
        && toState.equalsIgnoreCase(message.getToState())
        && resourceName.equalsIgnoreCase(message.getResourceName())
        && partitionName.equalsIgnoreCase(message.getPartitionName())) {
      return;
    }

    LOG.error("Invalid meesage: " + message.toString());
    LOG.error("From " + fromState + " to " + toState + " for " + partitionName);
  }

  public static String getMetaLocation(String cluster, String resourceName) {
    return "/metadata/" + cluster + "/" + resourceName + "/resource_meta";
  }

  // partition name is in format: test_0
  // S3 part prefix is in format: part-00000-
  public static String getS3PartPrefix(String partitionName) {
    String[] parts = partitionName.split("_");
    return String.format("part-%05d-", Integer.parseInt(parts[parts.length - 1]));
  }
}
