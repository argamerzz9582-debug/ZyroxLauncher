package git.artdeell.mojo.utils;

import git.artdeell.mojo.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class JSONUtils {
    public static List<String> insertJSONValueList(List<String> args, Map<String, String> keyValueMap) {
        for (int i = 0; i < args.size(); i++) {
            args.set(i, insertSingleJSONValue(args.get(i), keyValueMap));
        }
        return args;
    }

    public static String insertSingleJSONValue(String value, Map<String, String> keyValueMap) {
        String valueInserted = value;
        for (Map.Entry<String, String> keyValue : keyValueMap.entrySet()) {
            valueInserted = valueInserted.replace(
                    "${" + keyValue.getKey() + "}",
                    keyValue.getValue() == null ? "" : keyValue.getValue()
            );
        }
        return valueInserted;
    }

    public static <T> T readFromStream(InputStream file, Class<T> clazs) throws IOException {
        try (InputStreamReader streamReader = new InputStreamReader(file, StandardCharsets.UTF_8)) {
            return Tools.GLOBAL_GSON.fromJson(streamReader, clazs);
        }
    }

    /**
     * Atomically serializes {@code target} to {@code file} via a sibling {@code .tmp} file with
     * descriptor sync and optional {@code .bak} retention so interrupted writes never corrupt data.
     */
    public static void writeToFile(File file, Object target) throws IOException {
        writeToFileAtomic(file, target, false);
    }

    /**
     * Atomically serializes {@code target} to {@code file}. When {@code keepBackup} is true and a
     * non-empty prior file exists, preserves a rolling {@code .bak} copy for corruption recovery.
     */
    public static void writeToFileAtomic(File file, Object target, boolean keepBackup) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create parent directory for " + file.getName());
        }
        File tempFile = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile, false);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            Tools.GLOBAL_GSON.toJson(target, writer);
            writer.flush();
            try {
                fos.getFD().sync();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw e;
        }

        if (keepBackup && file.isFile() && file.length() > 2) {
            File bakFile = new File(parent, file.getName() + ".bak");
            copyFileQuietly(file, bakFile);
        }

        if (!tempFile.renameTo(file)) {
            if (file.exists() && !file.delete()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                throw new IOException("Failed to replace target JSON file: " + file.getName());
            }
            if (!tempFile.renameTo(file)) {
                copyFileStrict(tempFile, file);
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    private static void copyFileQuietly(File src, File dst) {
        try {
            copyFileStrict(src, dst);
        } catch (IOException ignored) {
        }
    }

    static void copyFileStrict(File src, File dst) throws IOException {
        byte[] buf = new byte[8192];
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst, false)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
            }
        }
    }

    public static <T> T readFromFile(File file, Class<T> clazs) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             InputStreamReader fileReader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            return Tools.GLOBAL_GSON.fromJson(fileReader, clazs);
        }
    }
}
