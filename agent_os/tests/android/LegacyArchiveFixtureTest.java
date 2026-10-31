import com.inceptless.traceweave.ArchiveCompat;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts a real archive exported by the pre-rename build and checks that the
 * compatibility rules would accept it.
 */
public final class LegacyArchiveFixtureTest {
    private static final Pattern FIELD = Pattern.compile("\"format\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws Exception {
        byte[] raw = Files.readAllBytes(Paths.get(args[0]));
        if (raw.length < 38 || raw[0] != 'T' || raw[1] != 'A' || raw[2] != 'O' || raw[3] != 'S')
            throw new IllegalStateException("not a TAOS archive");
        int iterations = ((raw[5] & 0xff) << 24) | ((raw[6] & 0xff) << 16) | ((raw[7] & 0xff) << 8) | (raw[8] & 0xff);
        byte[] salt = Arrays.copyOfRange(raw, 9, 25);
        byte[] nonce = Arrays.copyOfRange(raw, 25, 37);
        byte[] header = Arrays.copyOfRange(raw, 0, 37);
        byte[] ciphertext = Arrays.copyOfRange(raw, 37, raw.length);

        PBEKeySpec spec = new PBEKeySpec(args[1].toCharArray(), salt, iterations, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(header);
        String json = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);

        Matcher matcher = FIELD.matcher(json);
        if (!matcher.find()) throw new IllegalStateException("archive has no format field");
        String format = matcher.group(1);
        if (!ArchiveCompat.LEGACY_FORMAT.equals(format))
            throw new IllegalStateException("fixture is not a pre-rename archive: " + format);
        if (!ArchiveCompat.isFormatAccepted(format))
            throw new IllegalStateException("compatibility rules reject the legacy format");
        if (!json.contains("MigrationCanaryPreference"))
            throw new IllegalStateException("fixture content missing");
        System.out.println("PASS legacy_fixture format=" + format);
    }
}
