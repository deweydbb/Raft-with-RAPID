/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  The ASF licenses
 * this file to you under the Apache License, Version 2.0 (the
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

package net.data.technology.jraft;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import net.data.technology.jraft.extensions.FileBasedServerStateManager;
import net.data.technology.jraft.extensions.Log4jLoggerFactory;
import net.data.technology.jraft.extensions.RpcTcpClientFactory;
import net.data.technology.jraft.extensions.RpcTcpListener;
import org.h2.expression.function.SysInfoFunction;

public class App
{
    public static void main( String[] args ) throws Exception
    {
        if(args.length < 2){
            System.out.println("Please specify execution mode and a base directory for this instance.");
            return;
        }

        if(!"server".equalsIgnoreCase(args[0]) && !"client".equalsIgnoreCase(args[0]) && !"dummy".equalsIgnoreCase(args[0])){
            System.out.println("only client and server modes are supported");
            return;
        }

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2);

        Path baseDir = Paths.get(args[1]);
        if(!Files.isDirectory(baseDir)){
            System.out.printf("%s does not exist as a directory\n", args[1]);
            return;
        }

        FileBasedServerStateManager stateManager = new FileBasedServerStateManager(args[1]);
        ClusterConfiguration config = stateManager.loadClusterConfiguration();

        if("client".equalsIgnoreCase(args[0])){

            String[] restOfArgs = Arrays.copyOfRange(args, 2, args.length);
            executeAsClient(config, executor, restOfArgs);
            return;
        }

        // Server mode
        int port = 8000 + Integer.parseInt(args[2]);
        long startCount = 0;
        long endCount = 10;
        if (args.length >= 5) {
            startCount = Long.parseLong(args[3]);
            endCount = Long.parseLong(args[4]);
        }

        URI localEndpoint = new URI(config.getServer(stateManager.getServerId()).getEndpoint());
        RaftParameters raftParameters = new RaftParameters()
                .withElectionTimeoutUpper(5000)
                .withElectionTimeoutLower(3000)
                .withHeartbeatInterval(1500)
                .withRpcFailureBackoff(500)
                .withMaximumAppendingSize(200)
                .withLogSyncBatchSize(5)
                .withLogSyncStoppingGap(5)
                .withSnapshotEnabled(0) //disable snapshots
                .withSyncSnapshotBlockSize(0);
        KVStore mp = new KVStore(baseDir, port, startCount, endCount);
        RaftContext context = new RaftContext(
                stateManager,
                mp,
                raftParameters,
                new RpcTcpListener(localEndpoint.getPort(), executor),
                new Log4jLoggerFactory(),
                new RpcTcpClientFactory(executor),
                executor);
        RaftConsensus.run(context);
        System.out.println( "Press Enter to exit." );
        System.in.read();
        //mp.stop();
    }

    private static void executeAsClient(ClusterConfiguration configuration, ExecutorService executor, String[] args) throws Exception {
        RaftClient client = new RaftClient(new RpcTcpClientFactory(executor), configuration, new Log4jLoggerFactory());

        // immediately get config from seed server
        ClusterConfiguration newConfig = null;
        while (newConfig == null) {
            newConfig = client.getClusterConfig().get();
            Thread.sleep(100);
        }

        configuration = newConfig;
        client.setConfiguration(configuration);

        System.out.println("Args " + Arrays.toString(args));
        if (args.length > 0) {
            if ("throughput".equals(args[0])) {
                System.out.println("Executing throughput");
                executeThroughputTest(client, configuration, Arrays.copyOfRange(args, 1, args.length));
            } else {
                System.out.println("Unknown arguments to client " + Arrays.toString(args) + ", exiting");
            }
        } else {
            executeCommandLineClient(client, configuration);
        }

        System.exit(0);


    }

    private static void executeCommandLineClient(RaftClient client, ClusterConfiguration configuration) {
        Scanner in = new Scanner(System.in);
        System.out.print("Message:");
        while(in.hasNext()){
            try {
                String message = in.nextLine();
                if (message.startsWith("addsrv:")) {
                    String[] args = message.substring(7).split(":");

                    int serverId = Integer.parseInt(args[0]);
                    String ip = args.length >= 2 ? args[1] : "127.0.0.1";

                    ClusterServer server = new ClusterServer();
                    server.setEndpoint(String.format("tcp://%s:90%02d", ip, serverId));
                    server.setId(serverId);

                    boolean accepted = client.addServer(server).get();
                    System.out.println("Accepted: " + String.valueOf(accepted));
                    continue;
                }

                if (message.startsWith("rmsrv:")) {
                    String text = message.substring(6);
                    int serverId = Integer.parseInt(text.trim());
                    boolean accepted = client.removeServer(serverId).get();
                    if (!accepted && serverId == client.getLeaderId()) {
                        System.out.println("Server may not have been removed because it is the leader");
                    }
                    System.out.println("Accepted: " + String.valueOf(accepted));
                    continue;
                }

                if (message.startsWith("config")) {
                    ClusterConfiguration newConfig = client.getClusterConfig().get();
                    if (newConfig != null) {
                        configuration = newConfig;
                        System.out.println("Config: " + configuration.getServers());
                    } else {
                        System.out.println("Config:" + null);
                    }
                    continue;
                }

                if (message.startsWith("leader")) {
                    System.out.println("Leader: " + client.getLeaderId());
                    continue;
                }

                if (message.startsWith("put")) {
                    if (message.split(":").length == 3) {
                        String entry = message.substring(message.indexOf(':') + 1);
                        boolean accepted = client.appendEntries(new byte[][]{entry.getBytes()}).get();
                        System.out.println("Accepted: " + accepted);
                    }
                    continue;
                }

                if (message.startsWith("get")) {
                    message = message.substring(message.indexOf(':') + 1);
                    String[] split = message.split(":");
                    String key = split[0];
                    ClusterServer server;

                    if (split.length > 1) {
                        // user specified a specific server to read from
                        int serverId = Integer.parseInt(split[1]);
                        // check to see if config needs to be updated
                        if (configuration.getServer(serverId) == null) {
                            configuration = client.getClusterConfig().get();
                        }
                        server = configuration.getServer(serverId);
                    } else {
                        // get random server
                        List<ClusterServer> servers = configuration.getServers();
                        server = servers.get(new Random().nextInt(servers.size()));
                    }

                    String result = get(server, key);
                    System.out.println(result);
                }
            } catch (Exception e) {
                System.out.println("Error: " + e);
            } finally {
                System.out.print("Message:");
            }
        }

        System.exit(0);
    }

    private static void executeThroughputTest(RaftClient client, ClusterConfiguration configuration, String[] args) {
        String key = new Random().nextInt(1_000) + 1 + "";

        int leader = -1;
        while (leader == -1) {
            try {
                client.getClusterConfig().get();
                leader = client.getLeaderId();
                System.out.println("Leader: " + leader);

                if (leader == -1) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        long numPuts = Long.MAX_VALUE;
        if (args.length > 0) {
            numPuts = Long.parseLong(args[0]);
        }
        System.out.println("num puts: " + numPuts);

        long count = 0;
        CompletableFuture<Boolean> prev = new CompletableFuture<>();
        prev.complete(true);
        while (count < numPuts) {
            try {
                String entry = key + ":" + count;
                CompletableFuture<Boolean> nextPrev = client.appendEntries(new byte[][]{entry.getBytes()});
                //System.out.printf("print result of %s:%s: %s\n", key, value, accepted);
                if (count % 1000 == 0) {
                    System.out.println("count = " + count);
                    prev.get();
                    prev = nextPrev;
                }

                //Thread.sleep(1);
                count++;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String get(ClusterServer server, String key) {
        try {
            String endpoint = server.getEndpoint();
            endpoint = endpoint.substring(endpoint.lastIndexOf('/') + 1);

            String host = endpoint.split(":")[0];
            if ("localhost".equals(host)) {
                host = "127.0.0.1";
            }
            int port = Integer.parseInt(endpoint.split(":")[1]) - 1000;

            Socket socket = new Socket(host, port);
            OutputStream socketOutputStream = socket.getOutputStream();

            int msgLen = key.getBytes(StandardCharsets.UTF_8).length;
            byte[] bytes = new byte[msgLen + 4];
            for (int i = 0; i < 4; ++i) {
                int value = (msgLen >> (i * 8));
                bytes[i] = (byte) (value & 0xFF);
            }

            System.arraycopy(key.getBytes(StandardCharsets.UTF_8), 0, bytes, 4, msgLen);
            socketOutputStream.write(bytes);

            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            byte[] msgSize = new byte[4];
            in.read(msgSize);

            byte[] msg = new byte[little2big(msgSize) - 1];
            in.read(msg);
            in.close();
            socket.close();

            return new String(msg);
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static int little2big(byte[ ] b) {
        return ((b[3]&0xff)<<24)+((b[2]&0xff)<<16)+((b[1]&0xff)<<8)+(b[0]&0xff);
    }
}
