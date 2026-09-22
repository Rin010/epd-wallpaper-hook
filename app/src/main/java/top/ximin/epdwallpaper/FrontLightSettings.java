package top.ximin.epdwallpaper;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** Saves and restores the vendor cold/warm front-light preferences around a dark wake. */
final class FrontLightSettings {
    private static final String COLD_VALUE = "mogu_cold_led_value";
    private static final String WARM_VALUE = "mogu_warm_led_value";
    private static final String COLD_STATUS = "mogu_cold_led_status";
    private static final String WARM_STATUS = "mogu_warm_led_status";
    private static final String RECOVERY_FILE = "daily-refresh-front-light.properties";

    private final ContentResolver resolver;
    private final AtomicFile recovery;

    FrontLightSettings(Context context) throws IOException {
        resolver = context.getContentResolver();
        File root = new File(SystemWallpaperStore.ROOT);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("unable to create wallpaper system directory");
        }
        recovery = new AtomicFile(new File(root, RECOVERY_FILE));
    }

    Snapshot capture() {
        return new Snapshot(read(COLD_VALUE), read(WARM_VALUE),
                read(COLD_STATUS), read(WARM_STATUS));
    }

    void prepareRecovery(Snapshot snapshot) throws IOException {
        Properties properties = new Properties();
        snapshot.writeTo(properties);
        FileOutputStream output = null;
        try {
            output = recovery.startWrite();
            properties.store(output, "E-paper daily refresh front-light recovery");
            output.getFD().sync();
            recovery.finishWrite(output);
            output = null;
        } catch (Throwable error) {
            if (output != null) {
                recovery.failWrite(output);
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(error);
        }
    }

    void applyZero() throws IOException {
        writeAndVerify(COLD_VALUE, "0");
        writeAndVerify(WARM_VALUE, "0");
    }

    boolean restoreOwnedChanges(Snapshot snapshot) throws IOException {
        if (snapshot == null) {
            return recoverPending();
        }
        boolean cold = restoreIfOwned(COLD_VALUE, snapshot.coldValue);
        boolean warm = restoreIfOwned(WARM_VALUE, snapshot.warmValue);
        return cold && warm;
    }

    boolean recoverPending() throws IOException {
        Snapshot pending = readRecovery();
        if (pending == null) {
            return false;
        }
        boolean restored = restoreOwnedChanges(pending);
        if (restored) {
            clearRecovery();
        }
        return restored;
    }

    void clearRecovery() {
        recovery.delete();
    }

    private Snapshot readRecovery() throws IOException {
        if (!recovery.getBaseFile().isFile()) {
            return null;
        }
        Properties properties = new Properties();
        FileInputStream input = recovery.openRead();
        try {
            properties.load(input);
        } finally {
            input.close();
        }
        return Snapshot.readFrom(properties);
    }

    private boolean restoreIfOwned(String key, Value original) throws IOException {
        String current = read(key);
        if (!"0".equals(current)) {
            EpdWallpaperHook.log("front-light setting changed externally; preserving " + key);
            return true;
        }
        String restored = original.present ? original.value : null;
        if (!Settings.Global.putString(resolver, key, restored)) {
            return false;
        }
        String actual = read(key);
        if (restored == null ? actual != null : !restored.equals(actual)) {
            throw new IOException("unable to verify restored setting " + key);
        }
        return true;
    }

    private void writeAndVerify(String key, String value) throws IOException {
        if (!Settings.Global.putString(resolver, key, value)) {
            throw new IOException("unable to write setting " + key);
        }
        if (!value.equals(read(key))) {
            throw new IOException("unable to verify setting " + key);
        }
    }

    private String read(String key) {
        return Settings.Global.getString(resolver, key);
    }

    static final class Snapshot {
        final Value coldValue;
        final Value warmValue;
        final Value coldStatus;
        final Value warmStatus;

        Snapshot(String coldValue, String warmValue, String coldStatus, String warmStatus) {
            this.coldValue = new Value(coldValue);
            this.warmValue = new Value(warmValue);
            this.coldStatus = new Value(coldStatus);
            this.warmStatus = new Value(warmStatus);
        }

        private Snapshot(Value coldValue, Value warmValue,
                Value coldStatus, Value warmStatus) {
            this.coldValue = coldValue;
            this.warmValue = warmValue;
            this.coldStatus = coldStatus;
            this.warmStatus = warmStatus;
        }

        void writeTo(Properties properties) {
            coldValue.writeTo(properties, "cold.value");
            warmValue.writeTo(properties, "warm.value");
            coldStatus.writeTo(properties, "cold.status");
            warmStatus.writeTo(properties, "warm.status");
        }

        static Snapshot readFrom(Properties properties) throws IOException {
            return new Snapshot(Value.readFrom(properties, "cold.value"),
                    Value.readFrom(properties, "warm.value"),
                    Value.readFrom(properties, "cold.status"),
                    Value.readFrom(properties, "warm.status"));
        }
    }

    private static final class Value {
        final boolean present;
        final String value;

        Value(String value) {
            present = value != null;
            this.value = value;
        }

        private Value(boolean present, String value) {
            this.present = present;
            this.value = value;
        }

        void writeTo(Properties properties, String prefix) {
            properties.setProperty(prefix + ".present", Boolean.toString(present));
            if (present) {
                properties.setProperty(prefix + ".value", value);
            }
        }

        static Value readFrom(Properties properties, String prefix) throws IOException {
            String present = properties.getProperty(prefix + ".present");
            if (present == null) {
                throw new IOException("invalid front-light recovery file");
            }
            boolean exists = Boolean.parseBoolean(present);
            String value = properties.getProperty(prefix + ".value");
            if (exists && value == null) {
                throw new IOException("missing front-light recovery value");
            }
            return new Value(exists, value);
        }
    }
}
