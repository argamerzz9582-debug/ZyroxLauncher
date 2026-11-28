package git.artdeell.mojo.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import git.artdeell.mojo.ai.AiFriendConfigStore.ConfigData;
import git.artdeell.mojo.ai.AiFriendModels.AiRole;
import git.artdeell.mojo.ai.AiFriendModels.AiTurnResponse;
import git.artdeell.mojo.ai.AiFriendModels.BotAction;
import git.artdeell.mojo.ai.AiFriendModels.BotAuthMode;
import git.artdeell.mojo.ai.AiFriendModels.BotConnectionState;
import git.artdeell.mojo.ai.AiFriendModels.BotStatusSnapshot;
import git.artdeell.mojo.ai.AiFriendModels.ModelLoadState;
import git.artdeell.mojo.ai.AiFriendModels.ProviderMode;
import git.artdeell.mojo.ai.AiFriendModels.TargetConnectionMode;
import git.artdeell.mojo.ai.GgufRamAdvisor.FitSafety;
import git.artdeell.mojo.ai.GgufRamAdvisor.ModelFitAssessment;
import git.artdeell.mojo.ai.GgufRamAdvisor.RamReport;
import git.artdeell.mojo.ai.GgufRamAdvisor.RamTier;
import git.artdeell.mojo.ai.LlamaCppRuntimeBridge.GgufHeaderInfo;
import git.artdeell.mojo.security.LogRedactor;
import git.artdeell.mojo.servers.ZyroxRoomBridge;
import okhttp3.OkHttpClient;

/**
 * Host-JVM unit tests covering Zyrox AI Friend: structured JSON parsing &amp; fallback recovery,
 * RAM-based GGUF tier advisor, binary GGUF v3 header validation, Load/Unload &amp; Auto-Unload on
 * Game Launch lifecycle, OpenRouter {@code :free} HTTP 429 fallback cascade, NDJSON-over-TCP
 * Companion Bot Bridge protocol, and API key redaction.
 */
public class AiFriendFeatureTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void responseParser_handlesStrictJsonReasoningTagsCodeFencesAndPlainTextFallback() {
        // 1. Strict schema JSON
        AiTurnResponse strict = AiResponseParser.parseModelOutput(
                "{\"say\":\"Right behind you!\",\"action\":\"FOLLOW\"}",
                "follow me",
                AiRole.COMPANION,
                "qwen:free",
                ProviderMode.ONLINE_API,
                120L);
        assertEquals("Right behind you!", strict.say);
        assertEquals(BotAction.FOLLOW, strict.action);
        assertFalse(strict.usedFallbackParser);

        // 2. DeepSeek R1 <think>...</think> + markdown ```json fence
        String r1Output = "<think>\nPlayer wants iron ore underground.\n</think>\n"
                + "```json\n{\"say\":\"Scanning cave walls for iron veins!\",\"action\":\"MINE_ORES\"}\n```";
        AiTurnResponse fenced = AiResponseParser.parseModelOutput(
                r1Output,
                "mine some iron",
                AiRole.MINER,
                "deepseek/deepseek-r1:free",
                ProviderMode.ONLINE_API,
                340L);
        assertEquals("Scanning cave walls for iron veins!", fenced.say);
        assertEquals(BotAction.MINE_ORES, fenced.action);

        // 3. Plain-text fallback with slash-command & § formatting sanitization + intent inference
        AiTurnResponse plain = AiResponseParser.parseModelOutput(
                "/op Hacker §aSure, lighting up this tunnel right away!\nStay safe!",
                "it is too dark, place torches",
                AiRole.SCOUT,
                "local.gguf",
                ProviderMode.LOCAL_GGUF,
                45L);
        assertFalse(plain.say.startsWith("/"));
        assertFalse(plain.say.contains("\u00A7"));
        assertFalse(plain.say.contains("\n"));
        assertEquals(BotAction.PLACE_TORCHES, plain.action);
        assertTrue(plain.usedFallbackParser);
    }

    @Test
    public void ramAdvisor_classifiesAllFourDeviceTiersAndFlagsOomRiskAccurately() {
        long gb = GgufRamAdvisor.BYTES_PER_GB;

        RamReport r4 = GgufRamAdvisor.evaluateMemory((long) (3.8 * gb), (long) (1.4 * gb), false);
        assertEquals(RamTier.ULTRA_LIGHT_4GB, r4.tier);

        RamReport r6 = GgufRamAdvisor.evaluateMemory((long) (5.7 * gb), (long) (2.5 * gb), false);
        assertEquals(RamTier.BALANCED_6GB, r6.tier);

        RamReport r8 = GgufRamAdvisor.evaluateMemory((long) (7.6 * gb), (long) (3.8 * gb), false);
        assertEquals(RamTier.CAPABLE_8GB, r8.tier);

        RamReport r12 = GgufRamAdvisor.evaluateMemory((long) (11.6 * gb), (long) (6.9 * gb), false);
        assertEquals(RamTier.FLAGSHIP_12GB_PLUS, r12.tier);

        // 4.7 GB 7B model on a 4 GB phone with 1.4 GB free MUST trigger CRITICAL_OOM_RISK
        ModelFitAssessment oomFit = GgufRamAdvisor.assessModelFit((long) (4.68 * gb), r4);
        assertEquals(FitSafety.CRITICAL_OOM_RISK, oomFit.safety);

        // 490 MB 0.5B Q4 model on an 8 GB phone with 3.8 GB free MUST be SAFE
        ModelFitAssessment safeFit = GgufRamAdvisor.assessModelFit(490L * GgufRamAdvisor.BYTES_PER_MB, r8);
        assertEquals(FitSafety.SAFE, safeFit.safety);
    }

    @Test
    public void ggufHeaderInspectorAndLifecycle_validatesBinaryHeaderLoadsAndAutoUnloadsBeforeGameLaunch()
            throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            File dataDir = tmp.newFolder("zyrox_data");
            AiFriendManager manager = new AiFriendManager(dataDir, exec);
            GgufModelManager modelMgr = manager.getModelManager();

            // Write a synthetic valid GGUF v3 binary header with general.architecture="qwen2"
            File validGguf = new File(modelMgr.getModelsDirectory(), "qwen2.5-0.5b-instruct-q4_k_m.gguf");
            writeSyntheticGgufV3(validGguf, "qwen2", "Qwen2.5-0.5B-Instruct");

            GgufHeaderInfo info = LlamaCppRuntimeBridge.inspectGgufFile(validGguf);
            assertTrue(info.valid);
            assertEquals(3, info.version);
            assertEquals("qwen2", info.architecture);
            assertEquals("Qwen2.5-0.5B-Instruct", info.modelName);

            // Corrupt non-GGUF file must be rejected cleanly
            File badFile = new File(modelMgr.getModelsDirectory(), "corrupt.gguf");
            try (FileOutputStream out = new FileOutputStream(badFile)) {
                out.write("NOT_A_VALID_GGUF_BINARY_HEADER_1234567890".getBytes(StandardCharsets.UTF_8));
            }
            assertFalse(LlamaCppRuntimeBridge.inspectGgufFile(badFile).valid);
            assertTrue(badFile.delete());

            // Explicit Load -> Generate -> Auto-Unload on Game Launch
            assertEquals(ModelLoadState.UNLOADED, modelMgr.getLoadState());
            modelMgr.loadModelBlocking(validGguf.getName());
            assertEquals(ModelLoadState.LOADED, modelMgr.getLoadState());
            assertTrue(modelMgr.getRuntimeBridge().isModelLoaded());

            AiTurnResponse localTurn = manager.getLocalProvider().generateTurn(
                    "where should we mine diamonds?",
                    AiRole.MINER,
                    "ZyroxBuddy",
                    "Steve",
                    null,
                    Collections.<AiFriendModels.ChatHistoryEntry>emptyList());
            assertEquals(BotAction.MINE_ORES, localTurn.action);
            assertTrue(localTurn.say.toLowerCase().contains("diamond"));

            // Trigger pre-launch hook -> must auto-unload GGUF model to free RAM for Minecraft
            manager.onBeforeGameLaunch();
            assertEquals(ModelLoadState.UNLOADED, modelMgr.getLoadState());
            assertFalse(modelMgr.getRuntimeBridge().isModelLoaded());
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void onlineFreeApiProvider_cascadesAcrossFallbackModelsOnHttp429RateLimit() throws Exception {
        final AtomicInteger requestCount = new AtomicInteger(0);
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            final int port = server.getLocalPort();
            Thread httpWorker = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 2; i++) {
                        try (Socket client = server.accept()) {
                            client.setSoTimeout(3000);
                            InputStream in = client.getInputStream();
                            readHttpHeaders(in);
                            int attempt = requestCount.incrementAndGet();
                            OutputStream out = client.getOutputStream();
                            if (attempt == 1) {
                                String body = "{\"error\":{\"message\":\"Free model rate limited\"}}";
                                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                                String hdr = "HTTP/1.1 429 Too Many Requests\r\n"
                                        + "Retry-After: 0\r\n"
                                        + "Content-Type: application/json\r\n"
                                        + "Content-Length: " + bytes.length + "\r\n"
                                        + "Connection: close\r\n\r\n";
                                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                                out.write(bytes);
                            } else {
                                String body = "{\"choices\":[{\"message\":{\"content\":"
                                        + "\"{\\\"say\\\":\\\"Perimeter secure, watching for creepers!\\\",\\\"action\\\":\\\"GUARD\\\"}\"}}]}";
                                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                                String hdr = "HTTP/1.1 200 OK\r\n"
                                        + "Content-Type: application/json\r\n"
                                        + "Content-Length: " + bytes.length + "\r\n"
                                        + "Connection: close\r\n\r\n";
                                out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                                out.write(bytes);
                            }
                            out.flush();
                        } catch (Exception ignored) {
                            break;
                        }
                    }
                }
            });
            httpWorker.setDaemon(true);
            httpWorker.start();

            File dataDir = tmp.newFolder("online_test");
            AiFriendConfigStore store = new AiFriendConfigStore(dataDir);
            ConfigData cfg = store.load();
            cfg.baseApiUrl = "http://127.0.0.1:" + port + "/v1";
            cfg.selectedOnlineModel = "meta-llama/llama-3.3-70b-instruct:free";
            cfg.fallbackOnlineModels = Arrays.asList(
                    "meta-llama/llama-3.3-70b-instruct:free",
                    "qwen/qwen-2.5-72b-instruct:free");
            cfg.autoFallbackFreeModels = true;
            store.save(cfg);
            store.setApiKey("sk-or-v1-unitTestKey1234567890abcdef");

            OnlineFreeApiLlmProvider provider = new OnlineFreeApiLlmProvider(
                    store, new OkHttpClient(), true);
            AiTurnResponse turn = provider.generateTurn(
                    "guard our base",
                    AiRole.BODYGUARD,
                    "ZyroxBuddy",
                    "Alex",
                    null,
                    null);

            assertEquals(2, requestCount.get());
            assertEquals("qwen/qwen-2.5-72b-instruct:free", turn.modelUsed);
            assertEquals(BotAction.GUARD, turn.action);
            assertEquals("Perimeter secure, watching for creepers!", turn.say);
        }
    }

    @Test
    public void freeAnonymousRouterAndCatalog_routesKeylessAcrossProvidersAndEnforcesHttpsBoundary()
            throws Exception {
        // 1. Default brain mode out-of-the-box is FREE_ANONYMOUS (zero signup / zero API key)
        File dataDir = tmp.newFolder("anon_router_test");
        AiFriendConfigStore store = new AiFriendConfigStore(dataDir);
        ConfigData defaultCfg = store.load();
        assertEquals(ProviderMode.FREE_ANONYMOUS, ProviderMode.fromWire(defaultCfg.providerMode));
        assertTrue(AiProviderCatalog.ANONYMOUS_PROVIDERS.size() >= 5);
        assertTrue(AiProviderCatalog.API_KEY_PRESETS.size() >= 20);

        // 2. Local/LAN gateway HTTP boundary (allows OmniRoute 127.0.0.1 / 192.168.x.x, blocks public cleartext)
        assertTrue(AiProviderCatalog.isLocalOrPrivateLanHttpUrl("http://127.0.0.1:20128/v1"));
        assertTrue(AiProviderCatalog.isLocalOrPrivateLanHttpUrl("http://192.168.1.42:20128/v1"));
        assertFalse(AiProviderCatalog.isLocalOrPrivateLanHttpUrl("http://evil.example.com/v1"));

        // 3. Catalog /models parser filters utility/TTS/embedding models & respects free pricing
        String sampleModelsJson = "{\"data\":["
                + "{\"id\":\"kilo-auto/free\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}},"
                + "{\"id\":\"paid/model-x\",\"pricing\":{\"prompt\":\"0.002\",\"completion\":\"0.008\"}},"
                + "{\"id\":\"Qwen3-Embedding-8B\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}}"
                + "]}";
        assertEquals(Collections.singletonList("kilo-auto/free"),
                AiProviderCatalog.parseModelIdsFromCatalogJson(sampleModelsJson, true));

        // 4. Live FreeAnonymousRouterLlmProvider failover: Provider 1 returns 429 -> Provider 2 succeeds keyless!
        try (ServerSocket srvA = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             ServerSocket srvB = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {

            Thread workerA = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket c = srvA.accept()) {
                        readHttpHeaders(c.getInputStream());
                        byte[] b = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
                        String h = "HTTP/1.1 429 Too Many Requests\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
                        c.getOutputStream().write(h.getBytes(StandardCharsets.US_ASCII));
                        c.getOutputStream().write(b);
                    } catch (Exception ignored) {
                    }
                }
            });
            Thread workerB = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket c = srvB.accept()) {
                        readHttpHeaders(c.getInputStream());
                        byte[] b = ("{\"choices\":[{\"message\":{\"content\":"
                                + "\"{\\\"say\\\":\\\"Chopping birch logs for our shelter!\\\",\\\"action\\\":\\\"GATHER_WOOD\\\"}\"}}]}")
                                .getBytes(StandardCharsets.UTF_8);
                        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + b.length + "\r\nConnection: close\r\n\r\n";
                        c.getOutputStream().write(h.getBytes(StandardCharsets.US_ASCII));
                        c.getOutputStream().write(b);
                    } catch (Exception ignored) {
                    }
                }
            });
            workerA.setDaemon(true);
            workerB.setDaemon(true);
            workerA.start();
            workerB.start();

            AiProviderCatalog.AnonymousProviderSpec specA = new AiProviderCatalog.AnonymousProviderSpec(
                    "kilo", "Kilo Mock", "http://127.0.0.1:" + srvA.getLocalPort() + "/v1",
                    null, "200/hr", "", Collections.singletonList("kilo-auto/free"),
                    false, false, false, true, 10);
            AiProviderCatalog.AnonymousProviderSpec specB = new AiProviderCatalog.AnonymousProviderSpec(
                    "ovhcloud", "OVHcloud Mock", "http://127.0.0.1:" + srvB.getLocalPort() + "/v1",
                    null, "2 RPM", "", Collections.singletonList("Qwen3.8-27B"),
                    true, false, false, false, 10);

            FreeAnonymousRouterLlmProvider anonRouter = new FreeAnonymousRouterLlmProvider(
                    store, Arrays.asList(specA, specB), new OkHttpClient(), true);
            assertTrue(anonRouter.isReady());

            AiTurnResponse turn = anonRouter.generateTurn(
                    "gather wood for base",
                    AiRole.BUILDER,
                    "ZyroxBuddy",
                    "Steve",
                    null,
                    null);

            assertEquals(ProviderMode.FREE_ANONYMOUS, turn.providerMode);
            assertEquals(BotAction.GATHER_WOOD, turn.action);
            assertTrue(turn.modelUsed.startsWith("ovhcloud/"));
            assertTrue(anonRouter.getHealthSnapshot("ovhcloud").healthy);
        }
    }

    @Test
    public void targetResolverAndBridgeClient_resolvesLanRoomServerAndStreamsNdjsonCommands() throws Exception {
        // 1. Live Open-to-LAN log detection
        assertEquals(54321, AiFriendTargetResolver.parseLanPortFromGameLogLine(
                "[12:04:11] [Render thread/INFO]: [CHAT] Local game hosted on port 54321"));
        assertEquals(60123, AiFriendTargetResolver.parseLanPortFromGameLogLine(
                "[Server thread/INFO]: Started serving on 60123"));
        assertEquals(-1, AiFriendTargetResolver.parseLanPortFromGameLogLine(
                "[Render thread/INFO]: Created atlas textures"));

        // 2. ZYX-XXXX share code resolution
        String directCode = ZyroxRoomBridge.encodeDirectRoomCode("ZYX-7K9M", "192.168.1.50", 41234);
        ConfigData roomCfg = new ConfigData();
        roomCfg.targetMode = TargetConnectionMode.ZYROX_SELF_HOSTED.name();
        roomCfg.zyroxRoomCode = directCode;
        roomCfg.bridgeHost = "192.168.1.99";
        AiFriendTargetResolver.ResolvedTarget resolvedRoom = AiFriendTargetResolver.resolve(roomCfg);
        assertEquals("192.168.1.50", resolvedRoom.host);
        assertEquals(41234, resolvedRoom.port);

        // 3. Public server safety warning
        String hypixelWarn = AiFriendTargetResolver.inspectServerSafetyAndAuth(
                "mc.hypixel.net", BotAuthMode.MICROSOFT_DEVICE_CODE);
        assertNotNull(hypixelWarn);
        assertTrue(hypixelWarn.startsWith("Warning:"));

        // 4. NDJSON-over-TCP CompanionBotBridgeClient roundtrip
        ExecutorService exec = Executors.newCachedThreadPool();
        final CopyOnWriteArrayList<String> serverReceivedLines = new CopyOnWriteArrayList<>();
        final CountDownLatch connectedStateLatch = new CountDownLatch(1);
        final CountDownLatch executeReceivedLatch = new CountDownLatch(1);

        try (ServerSocket mockBridge = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            int bridgePort = mockBridge.getLocalPort();
            Thread bridgeThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (Socket peer = mockBridge.accept()) {
                        InputStream in = peer.getInputStream();
                        OutputStream out = peer.getOutputStream();
                        String line;
                        while ((line = CompanionBotBridgeClient.readBoundedLine(in, 8192)) != null) {
                            serverReceivedLines.add(line);
                            if (line.contains("\"type\":\"summon\"")) {
                                String stateFrame = "{\"type\":\"bot_state\",\"state\":\"CONNECTED\","
                                        + "\"botUsername\":\"ZyroxBuddy\",\"endpoint\":\"127.0.0.1:54321\","
                                        + "\"activeAction\":\"FOLLOW\",\"health\":20.0,\"food\":20,"
                                        + "\"detail\":\"Joined LAN world\"}\n";
                                out.write(stateFrame.getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            } else if (line.contains("\"type\":\"execute\"")) {
                                executeReceivedLatch.countDown();
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            bridgeThread.setDaemon(true);
            bridgeThread.start();

            CompanionBotBridgeClient client = new CompanionBotBridgeClient(
                    exec,
                    new CompanionBotBridgeClient.BridgeEventListener() {
                        @Override
                        public void onBotStatusChanged(BotStatusSnapshot snapshot) {
                            if (snapshot.state == BotConnectionState.CONNECTED) {
                                connectedStateLatch.countDown();
                            }
                        }

                        @Override
                        public void onWorldChatReceived(String username, String message, boolean isWhisper) {
                        }

                        @Override
                        public void onActionUpdate(BotAction action, String status, String detail) {
                        }

                        @Override
                        public void onBridgeNotice(String message) {
                        }
                    });

            ConfigData lanCfg = new ConfigData();
            lanCfg.targetMode = TargetConnectionMode.LAN_WORLD.name();
            lanCfg.lanAddress = "127.0.0.1:54321";
            AiFriendTargetResolver.ResolvedTarget lanTarget = AiFriendTargetResolver.resolve(lanCfg);

            client.connectAndSummonBlocking(
                    "127.0.0.1", bridgePort, "tok123",
                    lanTarget, "ZyroxBuddy", AiRole.COMPANION, "Steve", "1.21.4");

            assertTrue("Expected CONNECTED bot_state from bridge",
                    connectedStateLatch.await(3, TimeUnit.SECONDS));
            assertTrue(client.sendExecuteTurn("Chopping oak logs!", BotAction.GATHER_WOOD, "Steve"));
            assertTrue("Expected execute NDJSON frame at bridge",
                    executeReceivedLatch.await(3, TimeUnit.SECONDS));
            client.dismissAndClose();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void logRedactor_scrubsOpenRouterApiKeysAndBridgeTokens() {
        String raw = "Failed calling OpenRouter with key sk-or-v1-99887766554433221100aabbccddeeff "
                + "and {\"bridgeAuthToken\":\"mySecretBridgeToken999\"}";
        String safe = LogRedactor.redact(raw);
        assertFalse(safe.contains("99887766554433221100aabbccddeeff"));
        assertFalse(safe.contains("mySecretBridgeToken999"));
        assertTrue(safe.contains("[REDACTED]"));
    }

    // =========================================================================
    // Test Helpers
    // =========================================================================

    private static void writeSyntheticGgufV3(File outFile, String arch, String name) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(new byte[]{'G', 'G', 'U', 'F'});
        writeLeInt32(bos, 3);  // version = 3
        writeLeInt64(bos, 12L); // tensor_count = 12
        writeLeInt64(bos, 2L);  // metadata_kv_count = 2

        // KV 1: general.architecture = arch (type 8 = STRING)
        writeGgufString(bos, "general.architecture");
        writeLeInt32(bos, 8);
        writeGgufString(bos, arch);

        // KV 2: general.name = name (type 8 = STRING)
        writeGgufString(bos, "general.name");
        writeLeInt32(bos, 8);
        writeGgufString(bos, name);

        // Padding payload
        bos.write(new byte[128]);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(bos.toByteArray());
        }
    }

    private static void writeLeInt32(ByteArrayOutputStream bos, int val) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(val);
        bos.write(b.array());
    }

    private static void writeLeInt64(ByteArrayOutputStream bos, long val) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(val);
        bos.write(b.array());
    }

    private static void writeGgufString(ByteArrayOutputStream bos, String s) throws Exception {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        writeLeInt64(bos, utf8.length);
        bos.write(utf8);
    }

    private static void readHttpHeaders(InputStream in) throws Exception {
        int state = 0;
        while (true) {
            int b = in.read();
            if (b < 0) break;
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = (b == '\r') ? 1 : 0;
        }
    }
}
