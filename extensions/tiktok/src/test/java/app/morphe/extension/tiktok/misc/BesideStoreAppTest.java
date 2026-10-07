package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.ContextWrapper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What a renamed copy of TikTok writes into the {@code package} field of AppLog's registration
 * header. Robolectric's app runs under its own package, so here it stands for a copy that Clone
 * app renamed: its own name goes out as the store app's. A build under the store app's name, a
 * paused one, one without a context yet and every other field write what TikTok wrote.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BesideStoreAppTest {
    private Context context;
    private String ownPackage;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        ownPackage = context.getPackageName();
        PausedProcess.set(false);
    }

    @After public void tearDown() {
        Utils.setContext(context);
        PausedProcess.set(false);
    }

    @Test public void theTestAppRunsUnderAnotherPackageThanTheStoreApp() {
        assertNotEquals(BesideStoreApp.STORE_PACKAGE, ownPackage);
    }

    @Test public void aRenamedCopyRegistersUnderTheStorePackage() throws JSONException {
        JSONObject header = new JSONObject();
        assertSame("TikTok's call returns the header it wrote to",
                header, BesideStoreApp.putPackage(header, "package", ownPackage));
        assertEquals(BesideStoreApp.STORE_PACKAGE, header.getString("package"));
    }

    @Test public void theRestOfTheHeaderSaysWhatIsInstalled() throws JSONException {
        JSONObject header = new JSONObject();
        BesideStoreApp.putPackage(header, "real_package_name", ownPackage);
        BesideStoreApp.putPackage(header, "app_version", "47.1.4");
        BesideStoreApp.putPackage(header, "sig_hash", "1069fee951d60f3a194f14b337a3810d");
        assertEquals(ownPackage, header.getString("real_package_name"));
        assertEquals("47.1.4", header.getString("app_version"));
        assertEquals("1069fee951d60f3a194f14b337a3810d", header.getString("sig_hash"));
    }

    /** A build that kept TikTok's package name writes it unchanged. */
    @Test public void theStoreAppsOwnNameIsLeftAlone() throws JSONException {
        Utils.setContext(new ContextWrapper(context) {
            @Override public String getPackageName() {
                return BesideStoreApp.STORE_PACKAGE;
            }
        });
        assertFalse(BesideStoreApp.renamed(BesideStoreApp.STORE_PACKAGE));
        JSONObject header = new JSONObject();
        BesideStoreApp.putPackage(header, "package", BesideStoreApp.STORE_PACKAGE);
        assertEquals(BesideStoreApp.STORE_PACKAGE, header.getString("package"));
    }

    /** Only the copy's own name is swapped, never some other package that reaches the field. */
    @Test public void anotherPackageInTheFieldIsLeftAlone() throws JSONException {
        JSONObject header = new JSONObject();
        BesideStoreApp.putPackage(header, "package", "com.example.other");
        assertEquals("com.example.other", header.getString("package"));
    }

    @Test public void pausedTheCopyWritesItsOwnName() throws JSONException {
        PausedProcess.set(true);
        JSONObject header = new JSONObject();
        BesideStoreApp.putPackage(header, "package", ownPackage);
        assertEquals(ownPackage, header.getString("package"));
    }

    /** Before Hushfeed has a context the pause isn't decided, so TikTok's value goes out. */
    @Test public void withoutAContextTheCopyWritesItsOwnName() throws JSONException {
        Utils.setContext(null);
        try {
            JSONObject header = new JSONObject();
            BesideStoreApp.putPackage(header, "package", ownPackage);
            assertEquals(ownPackage, header.getString("package"));
        } finally {
            Utils.setContext(context);
        }
    }

    @Test public void aValueThatIsntTextGoesThrough() throws JSONException {
        JSONObject header = new JSONObject();
        BesideStoreApp.putPackage(header, "package", 7);
        assertEquals(7, header.getInt("package"));
        // JSONObject.put with a null value removes the field, as TikTok's own call would.
        BesideStoreApp.putPackage(header, "package", null);
        assertFalse(header.has("package"));
    }

    @Test public void aNullKeyThrowsAsTikToksCallDoes() {
        assertThrows(JSONException.class, () -> BesideStoreApp.putPackage(new JSONObject(), null, ownPackage));
    }
}
