package git.artdeell.mojo.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Host-JVM tests for the fail-closed update trust decision in
 * {@link AppUpdateChecker#isTrustedUpdateArchive}. Each case runs on several API levels because
 * the decision depends on what the platform can expose (signing certificates are always
 * available on API 28+).
 */
public class AppUpdateTrustTest {

    private static final String PKG = "com.zyrox.launcher";
    private static final String CERT_RELEASE =
            "66b2f822eceeea52bf0bcffde0d5f9be362de30495232aae7ef15e9e8ce6c5af";
    private static final String CERT_ROTATED =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String CERT_ATTACKER =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    private static final int[] MODERN_SDKS = {28, 31, 34, 36};
    private static final int[] LEGACY_SDKS = {21, 24, 26, 27};

    private static Set<String> certs(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void matchingPackageAndSignerIsTrustedOnEveryApiLevel() {
        for (int sdk : concat(LEGACY_SDKS, MODERN_SDKS)) {
            assertTrue("sdk " + sdk, AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    certs(CERT_RELEASE), certs(CERT_RELEASE), false));
        }
    }

    @Test
    public void certificateComparisonIgnoresArchiveHexCase() {
        assertTrue(AppUpdateChecker.isTrustedUpdateArchive(34, PKG, PKG,
                certs(CERT_RELEASE.toUpperCase()), certs(CERT_RELEASE), false));
    }

    @Test
    public void foreignSignerIsRejectedEvenWithValidChecksum() {
        for (int sdk : concat(LEGACY_SDKS, MODERN_SDKS)) {
            assertFalse("sdk " + sdk, AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    certs(CERT_ATTACKER), certs(CERT_RELEASE), true));
        }
    }

    @Test
    public void wrongOrMissingPackageNameIsRejected() {
        for (int sdk : MODERN_SDKS) {
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, "com.evil.launcher",
                    certs(CERT_RELEASE), certs(CERT_RELEASE), true));
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, null,
                    certs(CERT_RELEASE), certs(CERT_RELEASE), true));
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, null, PKG,
                    certs(CERT_RELEASE), certs(CERT_RELEASE), true));
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, "", "",
                    certs(CERT_RELEASE), certs(CERT_RELEASE), true));
        }
    }

    @Test
    public void unreadableCertificatesFailClosedOnApi28Plus() {
        // Previously this returned true ("fail-open"); API 28+ always exposes signers, so missing
        // data means a malformed or tampered archive.
        for (int sdk : MODERN_SDKS) {
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    null, certs(CERT_RELEASE), true));
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    certs(CERT_RELEASE), null, true));
            assertFalse(AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    Collections.<String>emptySet(), Collections.<String>emptySet(), true));
        }
    }

    @Test
    public void legacyDevicesWithoutArchiveSignaturesRequireVerifiedChecksum() {
        for (int sdk : LEGACY_SDKS) {
            assertTrue("sdk " + sdk, AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    null, certs(CERT_RELEASE), true));
            assertFalse("sdk " + sdk, AppUpdateChecker.isTrustedUpdateArchive(sdk, PKG, PKG,
                    null, certs(CERT_RELEASE), false));
        }
    }

    @Test
    public void rotatedSigningKeyStaysTrustedThroughLineage() {
        // APK Signature Scheme v3: the new archive's lineage still contains the original signer.
        assertTrue(AppUpdateChecker.isTrustedUpdateArchive(34, PKG, PKG,
                certs(CERT_RELEASE, CERT_ROTATED), certs(CERT_RELEASE), false));
        assertFalse(AppUpdateChecker.isTrustedUpdateArchive(34, PKG, PKG,
                certs(CERT_ROTATED), certs(CERT_RELEASE), true));
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
