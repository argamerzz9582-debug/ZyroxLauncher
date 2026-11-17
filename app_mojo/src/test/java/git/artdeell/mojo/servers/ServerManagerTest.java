package git.artdeell.mojo.servers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link ServerListManager} (binary NBT {@code servers.dat}, validation, quick-play)
 * and {@link ServerPinger} (SLP JSON parsing and VarInt codec).
 */
public class ServerManagerTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void validatesHostPortAndIpv6AndRejectsInvalidAddresses() {
        ServerListManager.ParsedHostPort def = ServerListManager.parseAndValidateAddress("mc.hypixel.net");
        assertEquals("mc.hypixel.net", def.host);
        assertEquals(25565, def.port);

        ServerListManager.ParsedHostPort custom = ServerListManager.parseAndValidateAddress("play.example.org:19132");
        assertEquals("play.example.org", custom.host);
        assertEquals(19132, custom.port);

        ServerListManager.ParsedHostPort v6 = ServerListManager.parseAndValidateAddress("[2001:db8::1]:25570");
        assertEquals("2001:db8::1", v6.host);
        assertEquals(25570, v6.port);

        String[] bad = {"", "   ", "example.com:0", "example.com:70000", "bad host.com", "http://mc.net", ":25565", "[::1"};
        for (String b : bad) {
            try {
                ServerListManager.parseAndValidateAddress(b);
                fail("Expected validation failure for: " + b);
            } catch (IllegalArgumentException expected) {
                // Passed
            }
        }
    }

    @Test
    public void roundTripsBinaryServersDatWithFavoritesGroupsAndJsonExport() throws Exception {
        File gameDir = tmp.newFolder("mc_dir");
        ServerListManager.ServerEntry s1 = new ServerListManager.ServerEntry("Survival SMP", "smp.example.net:25566");
        s1.favorite = true;
        s1.group = "Friends";
        s1.acceptTextures = true;

        ServerListManager.ServerEntry s2 = new ServerListManager.ServerEntry("Minigames", "play.minigames.net");

        ServerListManager.saveServers(gameDir, Arrays.asList(s1, s2));
        assertTrue(new File(gameDir, "servers.dat").isFile());

        List<ServerListManager.ServerEntry> loaded = ServerListManager.loadServers(gameDir);
        assertEquals(2, loaded.size());
        assertEquals("Survival SMP", loaded.get(0).name);
        assertEquals("smp.example.net:25566", loaded.get(0).address);
        assertTrue(loaded.get(0).favorite);
        assertEquals("Friends", loaded.get(0).group);
        assertTrue(loaded.get(0).acceptTextures);
        assertEquals("Minigames", loaded.get(1).name);
        assertFalse(loaded.get(1).favorite);

        // Portable JSON round-trip
        String exported = ServerListManager.exportServersJson(loaded);
        List<ServerListManager.ServerEntry> reimported = ServerListManager.importServersJson(exported);
        assertEquals(2, reimported.size());
        assertEquals("smp.example.net:25566", reimported.get(0).address);

        // Quick-connect args (modern 1.20+ vs legacy <=1.19.4)
        List<String> modernArgs = new ArrayList<>();
        ServerListManager.appendQuickConnectArgs(modernArgs, "play.example.net", true);
        assertEquals(Arrays.asList("--quickPlayMultiplayer", "play.example.net:25565"), modernArgs);

        List<String> legacyArgs = new ArrayList<>();
        ServerListManager.appendQuickConnectArgs(legacyArgs, "play.example.net:25577", false);
        assertEquals(Arrays.asList("--server", "play.example.net", "--port", "25577"), legacyArgs);
    }

    @Test
    public void parsesSlpStatusJsonStripsFormattingAndRoundTripsVarInts() throws Exception {
        String slpJson = "{\"version\":{\"name\":\"§aPaper 1.21.4\",\"protocol\":769},"
                + "\"players\":{\"max\":500,\"online\":142},"
                + "\"description\":{\"text\":\"\",\"extra\":[{\"text\":\"§6Welcome to \"},{\"text\":\"§lZyrox SMP!\"}]}}";
        ServerPinger.PingResult res = ServerPinger.parseStatusJson(slpJson, 38L);
        assertTrue(res.online);
        assertEquals(38L, res.latencyMs);
        assertEquals("Paper 1.21.4", res.versionName);
        assertEquals(142, res.onlinePlayers);
        assertEquals(500, res.maxPlayers);
        assertEquals("Welcome to Zyrox SMP!", res.motd);

        int[] samples = {0, 1, 127, 128, 25565, 2097151, -1};
        for (int v : samples) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ServerPinger.writeVarInt(new DataOutputStream(buf), v);
            int back = ServerPinger.readVarInt(new DataInputStream(new ByteArrayInputStream(buf.toByteArray())));
            assertEquals(v, back);
        }
    }
}
