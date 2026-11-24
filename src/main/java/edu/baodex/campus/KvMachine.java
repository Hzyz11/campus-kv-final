package edu.baodex.campus;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.*;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.*;
import org.apache.ratis.statemachine.*;
import org.apache.ratis.statemachine.impl.*;
import org.apache.ratis.util.MD5FileUtil;

public class KvMachine extends BaseStateMachine {
  final KvState state = new KvState();
  final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

  @Override
  public void initialize(RaftServer server, RaftGroupId group, RaftStorage disk)
      throws IOException {
    super.initialize(server, group, disk);
    storage.init(disk);
    reinitialize();
  }

  @Override
  public synchronized void reinitialize() throws IOException {
    var s = storage.loadLatestSnapshot();
    if (s == null) return;
    var f = s.getFile().getPath().toFile();
    verifySnapshot(f);
    state.restore(KvState.JSON.readTree(f));
    updateLastAppliedTermIndex(s.getTermIndex());
  }

  @Override
  public SimpleStateMachineStorage getStateMachineStorage() {
    return storage;
  }

  @Override
  public synchronized long takeSnapshot() throws IOException {
    var ti = getLastAppliedTermIndex();
    if (ti == null || ti.getIndex() < 0) return -1;
    var target = storage.getSnapshotFile(ti.getTerm(), ti.getIndex());
    var temp = Path.of(target + ".tmp");
    try (var out = new FileOutputStream(temp.toFile())) {
      out.write(KvState.JSON.writeValueAsBytes(state.export()));
      out.getFD().sync();
    }
    var md5 = fileDigest(temp.toFile());
    MD5FileUtil.saveMD5File(target, md5);
    Files.move(
        temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    storage.updateLatestSnapshot(
        new SingleFileSnapshotInfo(new FileInfo(target.toPath(), md5), ti));
    return ti.getIndex();
  }

  static org.apache.ratis.io.MD5Hash fileDigest(File file) throws IOException {
    try {
      var digest = java.security.MessageDigest.getInstance("MD5");
      try (var input = Files.newInputStream(file.toPath())) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = input.read(buffer)) >= 0) digest.update(buffer, 0, n);
      }
      return org.apache.ratis.io.MD5Hash.newInstance(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  static void verifySnapshot(File file) throws IOException {
    MD5FileUtil.verifySavedMD5(file, fileDigest(file));
  }

  @Override
  public synchronized CompletableFuture<Message> applyTransaction(TransactionContext trx) {
    try {
      var entry = trx.getLogEntry();
      var command =
          KvState.JSON.readTree(entry.getStateMachineLogEntry().getLogData().toStringUtf8());
      var r = state.apply(command);
      updateLastAppliedTermIndex(TermIndex.valueOf(entry));
      return CompletableFuture.completedFuture(Message.valueOf(r.toString()));
    } catch (Exception e) {
      return CompletableFuture.failedFuture(e);
    }
  }
}
