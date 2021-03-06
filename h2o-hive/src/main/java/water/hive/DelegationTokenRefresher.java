package water.hive;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import water.H2O;
import water.MRTask;
import water.Paxos;
import water.util.BinaryFileTransfer;
import water.util.FileUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DelegationTokenRefresher implements Runnable {

  public static final String H2O_AUTH_USER = "h2o.auth.user";
  public static final String H2O_AUTH_PRINCIPAL = "h2o.auth.principal";
  public static final String H2O_AUTH_KEYTAB = "h2o.auth.keytab";
  public static final String H2O_HIVE_JDBC_URL = "h2o.hive.jdbc.url";
  public static final String H2O_HIVE_PRINCIPAL = "h2o.hive.principal";

  public static void setup(Configuration conf, String tmpDir) throws IOException {
    if (!HiveTokenGenerator.isHiveDriverPresent()) {
      return;
    }
    String authUser = conf.get(H2O_AUTH_USER);
    String authPrincipal = conf.get(H2O_AUTH_PRINCIPAL);
    String authKeytab = conf.get(H2O_AUTH_KEYTAB);
    HiveTokenGenerator.HiveOptions options = HiveTokenGenerator.HiveOptions.make(conf);
    if (authPrincipal != null && authKeytab != null && options != null) {
      String authKeytabPath = writeKeytabToFile(authKeytab, tmpDir);
      new DelegationTokenRefresher(authPrincipal, authKeytabPath, authUser, options).start();
    } else {
      log("Delegation token refresh not active.", null);
    }
  }
  
  private static String writeKeytabToFile(String authKeytab, String tmpDir) throws IOException {
    FileUtils.makeSureDirExists(tmpDir);
    String fileName = tmpDir + File.separator + "auth_keytab";
    byte[] byteArr = BinaryFileTransfer.convertStringToByteArr(authKeytab);
    BinaryFileTransfer.writeBinaryFile(fileName, byteArr);
    return fileName;
  }

  private final ScheduledExecutorService _executor = Executors.newSingleThreadScheduledExecutor(
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("delegation-token-refresher-%d").build()
  );

  private final String _authPrincipal;
  private final String _authKeytabPath;
  private final String _authUser;
  private final HiveTokenGenerator.HiveOptions _hiveOptions;

  private final HiveTokenGenerator _hiveTokenGenerator = new HiveTokenGenerator();

  public DelegationTokenRefresher(
      String authPrincipal,
      String authKeytabPath,
      String authUser,
      HiveTokenGenerator.HiveOptions options
  ) {
    this._authPrincipal = authPrincipal;
    this._authKeytabPath = authKeytabPath;
    this._authUser = authUser;
    this._hiveOptions = options;
  }

  public void start() {
    _executor.scheduleAtFixedRate(this, 0, 1, TimeUnit.MINUTES);
  }
  
  private static void log(String s, Exception e) {
    System.out.println("TOKEN REFRESH: " + s);
    if (e != null) {
      e.printStackTrace(System.out);
    }
  }
  
  @Override
  public void run() {
    if (Paxos._cloudLocked && !(H2O.CLOUD.leader() == H2O.SELF)) {
      // cloud is formed the leader will take of subsequent refreshes
      _executor.shutdown();
      return;
    }
    try {
      refreshTokens();
    } catch (IOException | InterruptedException e) {
      log("Failed to refresh token.", e);
    }
  }
  
  private static class DistributeCreds extends MRTask<DistributeCreds> {
    
    private final byte[] _credsSerialized;

    private DistributeCreds(byte[] credsSerialized) {
      this._credsSerialized = credsSerialized;
    }

    @Override
    protected void setupLocal() {
      try {
        Credentials creds = deserialize();
        log("Updating credentials", null);
        UserGroupInformation.getCurrentUser().addCredentials(creds);
      } catch (IOException e) {
        log("Failed to update credentials", e);
      }
    }

    private Credentials deserialize() throws IOException {
      ByteArrayInputStream tokensBuf = new ByteArrayInputStream(_credsSerialized);
      Credentials creds = new Credentials();
      creds.readTokenStorageStream(new DataInputStream(tokensBuf));
      return creds;
    }
  }
  
  private Credentials getTokens(UserGroupInformation realUser, UserGroupInformation tokenUser) throws IOException, InterruptedException {
    return _hiveTokenGenerator.addHiveDelegationTokenAsUser(realUser, tokenUser, _hiveOptions);
  }
  
  private void distribute(Credentials creds) throws IOException {
    if (!Paxos._cloudLocked) {
      // skip token distribution in pre-cloud forming phase, only use credentials locally
      log("Updating credentials", null);
      UserGroupInformation.getCurrentUser().addCredentials(creds);
    } else {
      byte[] credsSerialized = serializeCreds(creds);
      new DistributeCreds(credsSerialized).doAllNodes();
    }
  }

  private void refreshTokens() throws IOException, InterruptedException {
    log("Log in from keytab as " + _authPrincipal, null);
    UserGroupInformation realUser = UserGroupInformation.loginUserFromKeytabAndReturnUGI(_authPrincipal, _authKeytabPath);
    UserGroupInformation tokenUser = realUser;
    if (_authUser != null) {
      log("Impersonate " + _authUser, null);
      // attempt to impersonate token user, this verifies that the real-user is able to impersonate tokenUser
      tokenUser = UserGroupInformation.createProxyUser(_authUser, tokenUser);
    }
    Credentials creds = getTokens(realUser, tokenUser);
    if (creds != null) {
      distribute(creds);
    } else {
      log("Failed to refresh delegation token.", null);
    }
  }

  private byte[] serializeCreds(Credentials creds) throws IOException {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
    DataOutputStream dataStream = new DataOutputStream(byteStream);
    creds.writeTokenStorageToStream(dataStream);
    return byteStream.toByteArray();
  }

}
