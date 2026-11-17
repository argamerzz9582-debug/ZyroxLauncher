package git.artdeell.mojo.utils;

import android.util.Base64;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtils {

    public static byte[] fileHash(MessageDigest messageDigest, File f) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(65535);
        try (FileInputStream fileInputStream = new FileInputStream(f);
             FileChannel channel = fileInputStream.getChannel()) {
            while (true) {
                buffer.rewind();
                if (channel.read(buffer) == -1) break;
                buffer.flip();
                messageDigest.update(buffer);
            }
        }
        return messageDigest.digest();
    }

    public static String longHash(String source) {
        try {
            byte[] stringBytes = source.getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(stringBytes);
            byte[] hashBytes = digest.digest();
            return Base64.encodeToString(hashBytes, Base64.NO_PADDING | Base64.URL_SAFE | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 digest unavailable", e);
        }
    }

    /**
     * Verifies downloaded Minecraft/Modrinth asset integrity against upstream-published SHA-1 hashes.
     */
    public static boolean compareSHA1(File f, String sourceSHA) throws IOException {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-1");
            byte[] wantedBytes = Hex.decodeHex(sourceSHA.toCharArray());
            byte[] localFileBytes = fileHash(messageDigest, f);
            return MessageDigest.isEqual(localFileBytes, wantedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 digest unavailable", e);
        } catch (DecoderException e) {
            throw new IOException("Invalid digest format for file " + f.getName());
        }
    }
}
