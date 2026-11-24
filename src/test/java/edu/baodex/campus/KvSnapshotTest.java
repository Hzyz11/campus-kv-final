package edu.baodex.campus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import org.apache.ratis.util.MD5FileUtil;
import static org.junit.jupiter.api.Assertions.*;
class KvSnapshotTest {
 @TempDir Path directory;
 @Test void changedSnapshotCannotPassStoredChecksumComparison()throws Exception{var f=directory.resolve("snapshot");Files.writeString(f,"{\"value\":1}");MD5FileUtil.saveMD5File(f.toFile(),KvMachine.fileDigest(f.toFile()));assertDoesNotThrow(()->KvMachine.verifySnapshot(f.toFile()));Files.writeString(f,"{\"value\":2}");assertThrows(IOException.class,()->KvMachine.verifySnapshot(f.toFile()));}
 @Test void missingDigestIsRejected()throws Exception{var f=directory.resolve("snapshot");Files.writeString(f,"{}");assertThrows(IOException.class,()->KvMachine.verifySnapshot(f.toFile()));}
}
